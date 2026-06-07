$hostsPath = "C:\Windows\System32\drivers\etc\hosts"
$entries = @(
    "127.0.0.1 mongo1",
    "127.0.0.1 mongo2",
    "127.0.0.1 mongo3"
)
$currentContent = Get-Content $hostsPath -Raw
$toAdd = @()
foreach ($entry in $entries) {
    $hostname = $entry.Split(' ')[1]
    if ($currentContent -notmatch $hostname) {
        $toAdd += $entry
    } else {
        Write-Host "Already exists: $entry"
    }
}
if ($toAdd.Count -gt 0) {
    $contentToAdd = "`r`n" + ($toAdd -join "`r`n")
    Add-Content -Path $hostsPath -Value $contentToAdd -Encoding ASCII
    foreach ($entry in $toAdd) { Write-Host "Added: $entry" }
}
Write-Host "Done."
