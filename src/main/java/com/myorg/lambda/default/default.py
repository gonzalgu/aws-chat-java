import json
import boto3
from botocore.exceptions import ClientError

def default_handler(event, context):
    print(event)
    connection_id = event['requestContext']['connectionId']    
    apigatewaymanagementapi = boto3.client(
        'apigatewaymanagementapi',
        endpoint_url=f"https://{event['requestContext']['domainName']}/{event['requestContext']['stage']}"
    )    
    try:
        connection_info = apigatewaymanagementapi.get_connection(ConnectionId=connection_id)
    except ClientError as e:
        print(e.response['Error']['Message'])
        return {
            'statusCode': 500,
        }
    
    connection_info['connectionID'] = connection_id    
    try:
        apigatewaymanagementapi.post_to_connection(
            ConnectionId=connection_id,
            Data=json.dumps({
                'message': 'Use the sendmessage route to send a message. Your info:',
                'connectionInfo': connection_info
            })
        )
    except ClientError as e:
        print(e.response['Error']['Message'])
        return {
            'statusCode': 500,
        }
        
    return {
        'statusCode': 200,
    }
