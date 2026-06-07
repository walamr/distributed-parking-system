$queues = @("transactions", "citations")

foreach ($queue in $queues) {
    rabbitmqadmin declare queue name=$queue durable=true
}

Write-Host "RabbitMQ queues created: transactions, citations"
