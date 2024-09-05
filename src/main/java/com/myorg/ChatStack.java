package com.myorg;

import software.constructs.Construct;

import java.nio.file.Path;
import java.nio.file.Paths;

import software.amazon.awscdk.Aws;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.aws_apigatewayv2_integrations.WebSocketLambdaIntegration;
import software.amazon.awscdk.services.apigatewayv2.WebSocketApi;
import software.amazon.awscdk.services.apigatewayv2.WebSocketRouteOptions;
import software.amazon.awscdk.services.apigatewayv2.WebSocketStage;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Code;

public class ChatStack extends Stack {
    public ChatStack(final Construct parent, final String id) {
        this(parent, id, null);
    }

    public ChatStack(final Construct parent, final String id, final StackProps props) {
        super(parent, id, props);
                
        //connections table 
        var connectionsTable = Table.Builder.create(this, "ConnectionsTable")
        .tableName(String.format("%s-cdk ConnectionTable", Aws.STACK_NAME))
        .partitionKey(
            Attribute            
            .builder()
            .name("connectionId")
            .type(AttributeType.STRING)                                
            .build())
        .removalPolicy(RemovalPolicy.DESTROY)        
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .build();

        // define the on connect lambda function        
        Function onConnectLambda = createFunction(
            "onConnect", 
            "connect.connect_handler", 
            "src/main/java/com/myorg/lambda/connect"
        );

        // disconnect lambda function
        Function onDisconnectLambda = createFunction(
            "onDisconnect", 
            "disconnect.disconnect_handler", 
            "src/main/java/com/myorg/lambda/disconnect"
        );
        

        // sendmessage lambda function
        Function onSendMessageLambda = createFunction(
            "onSendMessage", 
            "sendmessage.sendmessage_handler",
            "src/main/java/com/myorg/lambda/sendmessage"
        );

        // default handler lambda function
        Function defaultLambda = createFunction(
            "default", 
            "default.default_handler", 
            "src/main/java/com/myorg/lambda/default"
        );
        
        // add table name to lambda environemnt        
        onConnectLambda.addEnvironment("TABLE_NAME", connectionsTable.getTableName());            
        onDisconnectLambda.addEnvironment("TABLE_NAME", connectionsTable.getTableName());
        onSendMessageLambda.addEnvironment("TABLE_NAME", connectionsTable.getTableName());

        //grant lambda permission to access dynamodb
        connectionsTable.grantReadWriteData(onConnectLambda);
        connectionsTable.grantReadWriteData(onDisconnectLambda);
        connectionsTable.grantReadWriteData(onSendMessageLambda);
        

        //define the chat api
        WebSocketApi chatApi = WebSocketApi.Builder.create(this, "chatApi")   
        .routeSelectionExpression("$request.body.action")     
        .connectRouteOptions(WebSocketRouteOptions
            .builder()
            .integration(new WebSocketLambdaIntegration("ConnectIntegration", onConnectLambda))
            .build())
        .disconnectRouteOptions(WebSocketRouteOptions
            .builder()
            .integration(new WebSocketLambdaIntegration("DisconnectIntegration", onDisconnectLambda))
            .build())
        .defaultRouteOptions(WebSocketRouteOptions
            .builder()
            .integration(new WebSocketLambdaIntegration("DefaultIntegration", defaultLambda))
            .build()
        )
        .build();

        chatApi.addRoute("sendmessage", WebSocketRouteOptions.builder()
            .integration(new WebSocketLambdaIntegration("SendMessageIntegration", onSendMessageLambda))
            .build());

        //api permissions
        chatApi.grantManageConnections(defaultLambda);
        chatApi.grantManageConnections(onSendMessageLambda);

        var webSocketStage = WebSocketStage.Builder.create(this, "mystage")
            .webSocketApi(chatApi)
            .stageName("dev")
            .autoDeploy(true)  
            .build();                   

        //cloud formation output for dynamodb arn
        CfnOutput.Builder.create(this, "connectionTableOutput")
            .value(connectionsTable.getTableArn())
            .build();   

        CfnOutput.Builder.create(this, "apiUrl")
            .value(chatApi.getApiEndpoint())
            .build();


        //users table
        var usersTable = Table.Builder.create(this, "UsersTable")
        .tableName(String.format("%s-Users", Aws.STACK_NAME))
        .partitionKey(
            Attribute            
            .builder()
            .name("UserId")
            .type(AttributeType.STRING)                                
            .build())
        .removalPolicy(RemovalPolicy.DESTROY)        
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .build();

        CfnOutput.Builder.create(this, "UsersTable")
        .description("DynamoDB users table")
        .value(usersTable.getTableName())
        .build();
            
    }

    private Function createFunction(String name, String handler, String codePath){
        Function lambda = Function.Builder.create(this, name)
        .runtime(Runtime.PYTHON_3_12)
        .handler(handler)           
        .code(Code.fromAsset(codePath))
        .build();
        return lambda;
    }
}
