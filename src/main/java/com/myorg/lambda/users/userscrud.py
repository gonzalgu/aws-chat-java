import json
import uuid
import os
import boto3
from datetime import datetime
#from botocore.exceptions import ClientError

USERS_TABLE = os.getenv('USERS_TABLE', None)
dynamodb = boto3.resource("dynamodb")
ddbTable = dynamodb.Table(USERS_TABLE)


def handler(event, context):
    print(event)
    route_key = f"{event['httpMethod']} {event['resource']}"

    #set default response body
    response_body = {'Message': 'Unsopported route'}
    status_code = 400
    headers = {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*'
    }

    try:
        if route_key == 'GET /users':
            ddb_response = ddbTable.scan(Select='ALL_ATTRIBUTES')
            response_body = ddb_response['Items']
            status_code = 200

        #crud operations for single user
        if route_key == 'GET /user/{userId}':
            ddb_response = ddbTable.get_item(
                Key={'userId': event['pathParameter']['userId']}
            )
            if 'Item' in ddb_response:
                response_body = ddb_response['Item']
            else:
                response_body = {}
            status_code = 200
        
        #delete a user by id
        if route_key == 'DELETE /users/{userId}':
            ddbTable.delete_item(
                Key={'userId': event['pathParameter']['userId']}
            )
            response_body = {}
            status_code = 200

        #create a new user
        if route_key == 'POST /users':
            request_json = json.loads(event['body'])
            request_json['timestamp'] = datetime.now().isoformat()
            #generate unique id if not present in the request
            if 'userId' not in request_json:
                request_json['userId'] = str(uuid.uuid1())
            #update the database
            ddbTable.put_item(
                Item=request_json
            )
            response_body = request_json
            status_code = 200
        
        #update a specific user by ID
        if route_key == 'PUT /users/{userId}':
            #update items in the database
            request_json = json.loads(event['body'])
            request_json['timestamp'] = datetime.now().isoformat()
            request_json['userId'] = event['pathParameters']['userId']
            #update the database
            ddbTable.put_item(
                Item=request_json
            )
            response_body = request_json
            status_code = 200
    except Exception as err:
        status_code = 400
        response_body = {'Error': str(err)}
        print(str(err))
    return {
        'statusCode': status_code,
        'body': json.dumps(response_body),
        'headers': headers
    }

