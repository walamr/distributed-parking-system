Set-Location $PSScriptRoot
[System.IO.Directory]::SetCurrentDirectory($PSScriptRoot)

$outputDir = "red-team-delivery"
if (Test-Path $outputDir) { Remove-Item -Recurse -Force $outputDir }

# Create a folder for executables
New-Item -ItemType Directory -Force -Path "$outputDir/executables" | Out-Null
Copy-Item -Recurse "apps/queue-server/build/install/queue-server" "$outputDir/executables/queue-server"
Copy-Item -Recurse "apps/storage-server/build/install/storage-server" "$outputDir/executables/storage-server"
Copy-Item -Recurse "apps/customer-ui/build/install/customer-ui" "$outputDir/executables/customer-ui"
Copy-Item -Recurse "apps/peo-ui/build/install/peo-ui" "$outputDir/executables/peo-ui"
Copy-Item -Recurse "apps/mo-ui/build/install/mo-ui" "$outputDir/executables/mo-ui"

# Copy documentation
Copy-Item "*.md" "$outputDir/"
if (Test-Path "docs") { Copy-Item -Recurse "docs" "$outputDir/" }

# Copy configs and scripts
if (Test-Path "scripts") { Copy-Item -Recurse "scripts" "$outputDir/" }
if (Test-Path "docker") { Copy-Item -Recurse "docker" "$outputDir/" }
if (Test-Path "config") { Copy-Item -Recurse "config" "$outputDir/" }
Copy-Item "docker-compose.yml" "$outputDir/"
if (Test-Path ".env") { Copy-Item ".env" "$outputDir/" }
if (Test-Path ".env.example") { Copy-Item ".env.example" "$outputDir/" }

# Create the ZIP
if (Test-Path "ds-assignment-2-team-5.zip") { Remove-Item "ds-assignment-2-team-5.zip" }
Add-Type -A System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory("$outputDir", "ds-assignment-2-team-5.zip")

Write-Host "ZIP created successfully."
