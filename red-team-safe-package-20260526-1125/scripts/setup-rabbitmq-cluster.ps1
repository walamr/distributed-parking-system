param(
    [string]$PrimaryNode = "rabbitmq1",
    [string[]]$SecondaryNodes = @("rabbitmq2", "rabbitmq3")
)

# $ErrorActionPreference = "Stop"

function Invoke-RabbitMqCtl {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ContainerName,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    docker exec $ContainerName rabbitmqctl @Arguments
}

Write-Host "Waiting for Primary RabbitMQ container to respond..."
while ($true) {
    $ping = docker exec $PrimaryNode rabbitmq-diagnostics -q check_running 2>$null
    if ($LASTEXITCODE -eq 0) { break }
    Write-Host "Waiting for $PrimaryNode..."
    Start-Sleep -Seconds 3
}

Write-Host "Auto-clustering is enabled in rabbitmq.conf. Waiting for secondary nodes to join..."
Start-Sleep -Seconds 10

Write-Host "Configuring /parking Virtual Host and Users..."
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("add_vhost", "/parking")
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("delete_user", "guest") 2>$null

# Create/Update Users
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("add_user", "mulligan_admin", "admin_ultra_secure_99") 2>$null
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("change_password", "mulligan_admin", "admin_ultra_secure_99")
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("set_user_tags", "mulligan_admin", "administrator")

Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("add_user", "peo_service", "peo_secure_pass_2026") 2>$null
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("change_password", "peo_service", "peo_secure_pass_2026")

Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("add_user", "customer", "customer_secure_pass_2026") 2>$null
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("change_password", "customer", "customer_secure_pass_2026")

# Set Permissions (Hardening R1-A-01 / R1-A-02)
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("set_permissions", "-p", "/parking", "mulligan_admin", ".*", ".*", ".*")
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("set_permissions", "-p", "/parking", "customer", "^$", "^(amq\.default|transactions\.queue)$", "^$")
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("set_permissions", "-p", "/parking", "peo_service", "^$", "^(amq\.default|transactions\.queue|citations\.queue)$", "^(amq\.default|transactions\.queue|citations\.queue)$")


Write-Host "RabbitMQ cluster status:"
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("cluster_status")

Write-Host "RabbitMQ queue summary:"
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @(
    "list_queues",
    "name",
    "type",
    "durable",
    "arguments",
    "leader",
    "members")

Write-Host "RabbitMQ users:"
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("list_users")

Write-Host "RabbitMQ permissions on /parking:"
Invoke-RabbitMqCtl -ContainerName $PrimaryNode -Arguments @("list_permissions", "-p", "/parking")
