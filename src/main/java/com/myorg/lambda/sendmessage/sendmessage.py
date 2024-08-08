import json
import os
import boto3
from botocore.exceptions import ClientError

dynamodb = boto3.resource("dynamodb")
TABLE_NAME = os.environ['TABLE_NAME']

def sendmessage_handler(event, context):
    print(event)
    table = dynamodb.Table(TABLE_NAME)
    try:                
        response = table.scan()
        connections = response.get('Items', [])
    except ClientError as e:
        print(e.response['Error']['Message'])        
        return {
            'statusCode': 500,
        }
    
    apigatewaymanagementapi = boto3.client(
        'apigatewaymanagementapi',
        endpoint_url=f"https://{event['requestContext']['domainName']}/{event['requestContext']['stage']}"
    )
    message = json.loads(event['body'])['message']
    for connection in connections:
        connection_id = connection.get('connectionId')
        if connection_id != event['requestContext']['connectionId']:
            try:
                apigatewaymanagementapi.post_to_connection(
                    ConnectionId=connection_id,
                    Data=message
                )
            except ClientError as e:
                print(e.response['Error']['Message'])
    
    return {
        'statusCode': 200
    }
