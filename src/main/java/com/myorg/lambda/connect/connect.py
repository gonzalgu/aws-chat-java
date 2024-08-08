import json
import os
import boto3

dynamodb = boto3.resource("dynamodb")
TABLE_NAME = os.environ['TABLE_NAME']

def connect_handler(event, context):
    print(event)
    table = dynamodb.Table(TABLE_NAME)
    try:        
        table.put_item(Item={
            "connectionId": event['requestContext']['connectionId']
        })
        return {
            'statusCode': 200,
        }
    except Exception as exp:
        print(exp)
        return {
            'statusCode': 500,
        }

