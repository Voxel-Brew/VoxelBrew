param([Parameter(Mandatory = $true)][string]$Installer)
$ErrorActionPreference = 'Stop'
$installerPath = (Resolve-Path -LiteralPath $Installer).Path
$installed = Join-Path $env:LOCALAPPDATA 'Programs\VoxelBrew'
$shortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\VoxelBrew\VoxelBrew.lnk'
$classes = 'HKCU:\Software\Classes'
$handlers = Join-Path $classes '.vxb\OpenWithProgids'
$design = Join-Path $classes 'VoxelBrew.Design'
$evidence = Join-Path (Get-Location) 'gui\build\package-artifacts\installer'
if (Test-Path -LiteralPath $installed) { throw "Refusing to replace existing $installed" }
$existingType = Get-Item -LiteralPath 'Registry::HKEY_CLASSES_ROOT\.vxb' -ErrorAction SilentlyContinue
$userChoice = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\.vxb\UserChoice'
if (($existingType -and $existingType.GetValue('')) -or (Test-Path -LiteralPath $userChoice)) {
    throw 'The shell-open test requires a Windows profile without an existing .vxb default'
}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
try {
    $process = Start-Process -FilePath $installerPath -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Installer exited with $($process.ExitCode)" }
    if (!(Test-Path -LiteralPath $shortcut)) { throw "Missing Start menu shortcut: $shortcut" }
    $link = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcut)
    if ($link.TargetPath -ne (Join-Path $installed 'VoxelBrew.exe')) { throw 'Start menu target differs from installed launcher' }
    if ($link.WorkingDirectory -ne $installed) { throw 'Start menu working directory differs from installation' }
    if ((Get-Item -LiteralPath $handlers).GetValueNames() -notcontains 'VoxelBrew.Design') { throw 'Missing .vxb handler' }
    $command = (Get-Item -LiteralPath (Join-Path $design 'shell\open\command')).GetValue('')
    if ($command -ne ('"{0}\VoxelBrew.exe" --open "%1"' -f $installed)) { throw "Incorrect .vxb open command: $command" }
    $document = Join-Path $evidence ('associated design {0}.vxb' -f [guid]::NewGuid())
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../fixtures/reference-8mm.vxb') -Destination $document
    $opened = Start-Process -FilePath $document -Verb Open -PassThru
    if (!$opened) { throw 'Windows did not launch a process for the .vxb document' }
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        $log = Join-Path $env:USERPROFILE '.voxelbrew\log\app.log'
        do {
            $loaded = Select-String -LiteralPath $log -SimpleMatch " from $document" -Quiet -ErrorAction SilentlyContinue
            if ($loaded) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $deadline)
        if (!$loaded) { throw "VoxelBrew did not log opening $document" }
    } finally {
        if (!$opened.HasExited) { taskkill.exe /PID $opened.Id /T /F | Out-Null }
    }
    Push-Location $env:TEMP
    try {
        & (Join-Path $installed 'VoxelBrewCLI.exe') designer native-demo
        if ($LASTEXITCODE -ne 0) { throw 'Installed CLI designer native-demo failed' }
        $launched = Start-Process -FilePath $shortcut -PassThru
        Start-Sleep -Seconds 5
        if ($launched.HasExited) { throw 'Start menu launch exited before opening the GUI' }
        taskkill.exe /PID $launched.Id /T /F | Out-Null
    } finally { Pop-Location }
    $png = Join-Path $evidence 'installed-gui.png'
    Remove-Item -LiteralPath $png -ErrorAction SilentlyContinue
    $smoke = Start-Process -FilePath (Join-Path $installed 'VoxelBrew.exe') -ArgumentList '--smoke-test', '--smoke-output', ('"{0}"' -f $png) -WorkingDirectory $env:TEMP -Wait -PassThru
    if ($smoke.ExitCode -ne 0 -or !(Test-Path -LiteralPath $png)) { throw 'Installed GUI render-and-pick smoke test failed' }
} finally {
    $uninstaller = Join-Path $installed 'unins000.exe'
    if (Test-Path -LiteralPath $uninstaller) {
        $uninstall = Start-Process -FilePath $uninstaller -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru
        if ($uninstall.ExitCode -ne 0) { throw "Uninstaller exited with $($uninstall.ExitCode)" }
    }
}
if (Test-Path -LiteralPath $installed) { throw "Uninstall left $installed" }
if (Test-Path -LiteralPath $design) { throw 'Uninstall left the VoxelBrew file type' }
if ((Test-Path -LiteralPath $handlers) -and ((Get-Item -LiteralPath $handlers).GetValueNames() -contains 'VoxelBrew.Design')) {
    throw 'Uninstall left VoxelBrew in the .vxb handler list'
}
if ((Test-Path -LiteralPath "$classes\.vxb") -and ((Get-Item -LiteralPath "$classes\.vxb").GetValue('') -eq 'VoxelBrew.Design')) {
    throw 'Uninstall left a stale .vxb default'
}
