#!/usr/bin/env sh
set -eu

rabbitmqadmin declare queue name=transactions durable=true
rabbitmqadmin declare queue name=citations durable=true

echo "RabbitMQ queues created: transactions, citations"
