package dev.paulguevara.iwh.process;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** Same key schema as infra/dynamodb.tf, created in DynamoDB Local. */
final class TestTables {

    private TestTables() { }

    static void create(DynamoDbClient client, Tables tables) {
        hashOnly(client, tables.processedEvents());
        hashAndRange(client, tables.ledger());
        hashOnly(client, tables.balances());
    }

    private static void hashOnly(DynamoDbClient client, String name) {
        client.createTable(r -> r.tableName(name)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attr("pk"))
                .keySchema(key("pk", KeyType.HASH)));
    }

    private static void hashAndRange(DynamoDbClient client, String name) {
        client.createTable(r -> r.tableName(name)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attr("pk"), attr("sk"))
                .keySchema(key("pk", KeyType.HASH), key("sk", KeyType.RANGE)));
    }

    private static AttributeDefinition attr(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
