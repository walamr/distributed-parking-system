# Script to add host mappings to Windows hosts file
$hostsPath = "C:\Windows\System32\drivers\etc\hosts"
$mappings = "`r`n127.0.0.1 mongo1 mongo2 mongo3 rabbitmq1 rabbitmq2 rabbitmq3"
Add-Content -Path $hostsPath -Value $mappings -ErrorAction Stop
Write-Output "Hosts added successfully!"
