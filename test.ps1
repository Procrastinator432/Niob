[CmdletBinding()]
param(
    [ValidateSet('All', 'Rust', 'Android')]
    [string]$Target = 'All',
    [string]$Filter = '',
    [ValidateRange(1, 3600)]
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$resultRoot = Join-Path $projectDir '.test-results'
$runDir = Join-Path $resultRoot ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6))
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$results = @()

function Find-Executable([string]$Name, [string]$Fallback = '') {
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
    if ($Fallback -and (Test-Path -LiteralPath $Fallback)) { return $Fallback }
    throw "$Name was not found. Add it to PATH (or set JAVA_HOME for Java)."
}

function Invoke-TestProcess([string]$Executable, [string[]]$Arguments, [string]$LogName) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $Executable
    # Windows CommandLineToArgvW quoting, including empty arguments and trailing backslashes.
    $info.Arguments = ($Arguments | ForEach-Object {
        '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
    }) -join ' '
    $info.WorkingDirectory = $projectDir
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $info.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw "Could not start $Executable" }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $timedOut = -not $process.WaitForExit($TimeoutSeconds * 1000)
        if ($timedOut) {
            # taskkill /T also stops child test executables launched by Cargo on Windows PowerShell 5.1.
            & "$env:SystemRoot\System32\taskkill.exe" /PID $process.Id /T /F *> $null
            if (-not $process.WaitForExit(10000)) { throw 'Timed-out test process could not be stopped' }
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $output = $stdout + $stderr
        if ($timedOut) { $output += "`nTIMEOUT after $TimeoutSeconds seconds`n" }
        [IO.File]::WriteAllText((Join-Path $runDir $LogName), $output, (New-Object System.Text.UTF8Encoding($false)))
        if ($output) { Write-Host $output.TrimEnd() }
        $code = $process.ExitCode
        if ($timedOut) { $code = 124 }
        return [pscustomobject]@{ ExitCode = $code; TimedOut = $timedOut; Output = $output }
    } finally {
        $process.Dispose()
    }
}

if ($Target -eq 'All' -or $Target -eq 'Rust') {
    Write-Host 'Running Rust unit and regression tests...' -ForegroundColor Cyan
    try {
        $cargo = Find-Executable 'cargo.exe' (Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe')
        $arguments = @('test', '--offline', '--no-fail-fast', '--manifest-path', (Join-Path $projectDir 'app\Cargo.toml'), '--', '--test-threads=1')
        if ($Filter) { $arguments += $Filter }
        $result = Invoke-TestProcess $cargo $arguments 'rust.log'
        $passed = 0
        $failed = 0
        foreach ($match in [regex]::Matches($result.Output, 'test result: \w+\. (\d+) passed; (\d+) failed;')) {
            $passed += [int]$match.Groups[1].Value
            $failed += [int]$match.Groups[2].Value
        }
        $code = $result.ExitCode
        $message = ''
        if ($result.TimedOut) { $message = "Rust test process timed out after $TimeoutSeconds seconds." }
        if ($code -eq 0 -and $passed -eq 0) { $code = 2; $message = 'Filter matched no Rust tests.' }
        $results += [pscustomobject]@{ Target = 'Rust'; ExitCode = $code; Passed = $passed; Failed = $failed; Message = $message; Log = 'rust.log' }
    } catch {
        $results += [pscustomobject]@{ Target = 'Rust'; ExitCode = 2; Passed = 0; Failed = 0; Message = $_.Exception.Message; Log = 'rust.log' }
    }
}

if ($Target -eq 'All' -or $Target -eq 'Android') {
    Write-Host 'Running Android production-engine tests on the JVM...' -ForegroundColor Cyan
    try {
        $javaFallback = ''
        $javacFallback = ''
        if ($env:JAVA_HOME) {
            $javaFallback = Join-Path $env:JAVA_HOME 'bin\java.exe'
            $javacFallback = Join-Path $env:JAVA_HOME 'bin\javac.exe'
        }
        $java = Find-Executable 'java.exe' $javaFallback
        $javac = Find-Executable 'javac.exe' $javacFallback
        $classes = Join-Path $runDir 'android-classes'
        New-Item -ItemType Directory -Path $classes -Force | Out-Null
        $compile = Invoke-TestProcess $javac @(
            '--release', '8', '-encoding', 'UTF-8', '-Xlint:-options', '-d', $classes,
            (Join-Path $projectDir 'app\android\src\com\jadecalculator\materialyou\CalculatorEngine.java'),
            (Join-Path $projectDir 'app\android\tests\com\jadecalculator\materialyou\CalculatorRegressionTest.java')
        ) 'android-compile.log'
        if ($compile.ExitCode -ne 0) { throw 'Android test compilation failed; see android-compile.log.' }
        $xmlPath = Join-Path $runDir 'android-junit.xml'
        $result = Invoke-TestProcess $java @(
            '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', $classes,
            'com.jadecalculator.materialyou.CalculatorRegressionTest',
            (Join-Path $projectDir 'app\tests\data\regressions.tsv'), $Filter, $xmlPath
        ) 'android.log'
        $passed = 0
        $failed = 0
        if (Test-Path -LiteralPath $xmlPath) {
            [xml]$junit = Get-Content -LiteralPath $xmlPath -Raw -Encoding UTF8
            $failed = [int]$junit.testsuite.failures
            $passed = [int]$junit.testsuite.tests - $failed
        }
        $code = $result.ExitCode
        $message = ''
        if ($result.TimedOut) {
            $message = "Android test process timed out after $TimeoutSeconds seconds."
        } elseif (-not (Test-Path -LiteralPath $xmlPath)) {
            $message = 'No JUnit report produced; see android.log (for example, a filter without matches).'
        }
        if ($code -eq 0 -and $passed -eq 0) { $code = 2; $message = 'Filter matched no Android tests.' }
        $results += [pscustomobject]@{ Target = 'Android'; ExitCode = $code; Passed = $passed; Failed = $failed; Message = $message; Log = 'android.log' }
    } catch {
        $results += [pscustomobject]@{ Target = 'Android'; ExitCode = 2; Passed = 0; Failed = 0; Message = $_.Exception.Message; Log = 'android-compile.log' }
    }
}

$summary = [pscustomobject]@{
    GeneratedAt = (Get-Date).ToString('o')
    Filter = $Filter
    RunDirectory = $runDir
    Results = @($results)
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDir 'summary.json') -Encoding UTF8
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $resultRoot 'latest.json') -Encoding UTF8
$markdown = @('# Latest regression test run', '', '| Target | Passed | Failed | Exit code | Log |', '|---|---:|---:|---:|---|')
foreach ($result in $results) {
    $markdown += "| $($result.Target) | $($result.Passed) | $($result.Failed) | $($result.ExitCode) | [$($result.Log)](<$runDir\$($result.Log)>) |"
    if ($result.Message) { $markdown += "`n$($result.Target): $($result.Message)`n" }
}
$markdown += @('', "Filter: '$Filter'", '', 'Exit code 0 means all selected tests passed. Existing defects are real failures, not ignored or expected failures.')
$markdown | Set-Content -LiteralPath (Join-Path $resultRoot 'latest.md') -Encoding UTF8
Write-Host "`nResults: $(Join-Path $resultRoot 'latest.md')"
$results | Format-Table Target, Passed, Failed, ExitCode, Message -AutoSize | Out-Host
if (@($results | Where-Object { $_.ExitCode -ne 0 }).Count -gt 0) { exit 1 }
exit 0
