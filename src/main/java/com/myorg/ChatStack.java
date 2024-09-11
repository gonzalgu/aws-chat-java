package com.myorg;

import software.constructs.Construct;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import software.amazon.awscdk.Aws;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.aws_apigatewayv2_integrations.WebSocketLambdaIntegration;
import software.amazon.awscdk.services.apigateway.Authorizer;
import software.amazon.awscdk.services.apigateway.CognitoUserPoolsAuthorizer;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.apigatewayv2.WebSocketApi;
import software.amazon.awscdk.services.apigatewayv2.WebSocketRouteOptions;
import software.amazon.awscdk.services.apigatewayv2.WebSocketStage;
import software.amazon.awscdk.services.cognito.AuthFlow;
import software.amazon.awscdk.services.cognito.AutoVerifiedAttrs;
import software.amazon.awscdk.services.cognito.CognitoDomainOptions;
import software.amazon.awscdk.services.cognito.OAuthFlows;
import software.amazon.awscdk.services.cognito.OAuthScope;
import software.amazon.awscdk.services.cognito.OAuthSettings;
import software.amazon.awscdk.services.cognito.SignInAliases;
import software.amazon.awscdk.services.cognito.StandardAttribute;
import software.amazon.awscdk.services.cognito.StandardAttributes;
import software.amazon.awscdk.services.cognito.UserPool;
import software.amazon.awscdk.services.cognito.UserPoolClient;
import software.amazon.awscdk.services.cognito.UserPoolClientIdentityProvider;
import software.amazon.awscdk.services.cognito.UserPoolClientOptions;
import software.amazon.awscdk.services.cognito.UserPoolDomainOptions;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.events.targets.ApiGateway;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.lambda.nodejs.NodejsFunction;
import software.amazon.awscdk.services.ses.actions.Lambda;
import software.amazon.awscdk.services.lambda.Code;

public class ChatStack extends Stack {
    public ChatStack(final Construct parent, final String id) {
        this(parent, id, null);
    }

    public ChatStack(final Construct parent, final String id, final StackProps props) {
        super(parent, id, props);

        // connections table
        var connectionsTable = Table.Builder.create(this, "ConnectionsTable")
                .tableName(String.format("%s-ConnectionTable", Aws.STACK_NAME))
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
                "src/main/java/com/myorg/lambda/connect");

        // disconnect lambda function
        Function onDisconnectLambda = createFunction(
                "onDisconnect",
                "disconnect.disconnect_handler",
                "src/main/java/com/myorg/lambda/disconnect");

        // sendmessage lambda function
        Function onSendMessageLambda = createFunction(
                "onSendMessage",
                "sendmessage.sendmessage_handler",
                "src/main/java/com/myorg/lambda/sendmessage");

        // default handler lambda function
        Function defaultLambda = createFunction(
                "default",
                "default.default_handler",
                "src/main/java/com/myorg/lambda/default");

        // add table name to lambda environemnt
        onConnectLambda.addEnvironment("TABLE_NAME", connectionsTable.getTableName());
        onDisconnectLambda.addEnvironment("TABLE_NAME", connectionsTable.getTableName());
        onSendMessageLambda.addEnvironment("TABLE_NAME", connectionsTable.getTableName());

        // grant lambda permission to access dynamodb
        connectionsTable.grantReadWriteData(onConnectLambda);
        connectionsTable.grantReadWriteData(onDisconnectLambda);
        connectionsTable.grantReadWriteData(onSendMessageLambda);

        // define the chat api
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
                        .build())
                .build();

        chatApi.addRoute("sendmessage", WebSocketRouteOptions.builder()
                .integration(new WebSocketLambdaIntegration("SendMessageIntegration", onSendMessageLambda))
                .build());

        // api permissions
        chatApi.grantManageConnections(defaultLambda);
        chatApi.grantManageConnections(onSendMessageLambda);

        var webSocketStage = WebSocketStage.Builder.create(this, "mystage")
                .webSocketApi(chatApi)
                .stageName("dev")
                .autoDeploy(true)
                .build();

        // cloud formation output for dynamodb arn
        CfnOutput.Builder.create(this, "connectionTableOutput")
                .value(connectionsTable.getTableArn())
                .build();

        CfnOutput.Builder.create(this, "apiUrl")
                .value(chatApi.getApiEndpoint())
                .build();

        // users table
        var usersTable = Table.Builder.create(this, "UsersTable")
                .tableName(String.format("%s-Users", Aws.STACK_NAME))
                .partitionKey(
                        Attribute
                                .builder()
                                .name("userId")
                                .type(AttributeType.STRING)
                                .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();

        CfnOutput.Builder.create(this, "UsersTableOutput")
                .description("DynamoDB users table")
                .value(usersTable.getTableName())
                .build();

        // create a lambda for crud operations on the users table.
        Function usersFunction = Function.Builder.create(this, "UsersFunction")
                .runtime(Runtime.PYTHON_3_12)
                .handler("userscrud.handler")
                .code(Code.fromAsset("src/main/java/com/myorg/lambda/users/"))
                .tracing(Tracing.ACTIVE)
                .timeout(Duration.seconds(100))
                .environment(Map.of("USERS_TABLE", usersTable.getTableName()))
                .build();

        usersTable.grantReadWriteData(usersFunction);

        var userPool = UserPool.Builder.create(this, "UserPool")
                .userPoolName(String.format("%s-user-pool", Aws.STACK_NAME))
                .selfSignUpEnabled(true)
                .signInAliases(SignInAliases.builder().email(true).build())
                .autoVerify(AutoVerifiedAttrs.builder().email(true).build())
                .standardAttributes(StandardAttributes.builder()
                        .email(StandardAttribute.builder()
                                .mutable(true)
                                .required(true)
                                .build())
                        .fullname(StandardAttribute.builder()
                                .required(true)
                                .mutable(true).build())
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        var authorizer = CognitoUserPoolsAuthorizer.Builder.create(this, "Authorizer")
                .authorizerName(String.format("%s-user-api-authorizer", Aws.STACK_NAME))
                .cognitoUserPools(List.of(userPool))
                .build();

        var userPoolClient = userPool.addClient(
                "UserPoolClient",
                UserPoolClientOptions.builder()
                        .userPoolClientName(String.format("%s-user-pool-client", Aws.STACK_NAME))
                        .authFlows(AuthFlow.builder()
                                .userPassword(true)
                                .userSrp(true)
                                .build())
                        .preventUserExistenceErrors(true)
                        .refreshTokenValidity(Duration.days(30))
                        .supportedIdentityProviders(List.of(UserPoolClientIdentityProvider.COGNITO))
                        .oAuth(OAuthSettings.builder()
                                .flows(OAuthFlows.builder()
                                        .authorizationCodeGrant(true)
                                        .build())
                                .scopes(List.of(
                                        OAuthScope.EMAIL,
                                        OAuthScope.OPENID))
                                .callbackUrls(List.of("http://localhost"))
                                .build())
                        .build());

        var userPoolClientId = userPoolClient.getUserPoolClientId();
        userPool.addDomain(
                "user-pool-domain",
                UserPoolDomainOptions.builder()
                        .cognitoDomain(CognitoDomainOptions.builder()
                                .domainPrefix(userPoolClientId)
                                .build())
                        .build());

        var usersApi = RestApi.Builder.create(this, "UsersApi")
                .defaultMethodOptions(MethodOptions.builder().authorizer(authorizer).build())
                .deployOptions(StageOptions.builder()
                        .stageName("prod")
                        .build())
                .build();

        var users = usersApi.getRoot().addResource("users");
        users.addMethod("GET", LambdaIntegration.Builder.create(usersFunction).build());
        users.addMethod("POST", LambdaIntegration.Builder.create(usersFunction).build());

        var user = users.addResource("{userId}");
        user.addMethod("DELETE", LambdaIntegration.Builder.create(usersFunction).build());
        user.addMethod("GET", LambdaIntegration.Builder.create(usersFunction).build());
        user.addMethod("PUT", LambdaIntegration.Builder.create(usersFunction).build());

        Tags.of(usersApi).add("Name", String.format("%s-userapi", Aws.STACK_NAME));
        Tags.of(usersApi).add("Stack", Aws.STACK_NAME);

        CfnOutput.Builder.create(this, "usersAPIUrl")
                .value(usersApi.getUrl())
                .build();

        // Cognito outputs
        CfnOutput.Builder.create(this, "UserPoolID")
                .description("Cognito User Pool ID")
                .value(userPool.getUserPoolId())
                .build();

        CfnOutput.Builder.create(this, "UserPoolClient")
                .description("Cognito User Pool Application Client ID")
                .value(userPoolClientId)
                .build();

        CfnOutput.Builder.create(this, "CognitoLoginURL")
                .description("Cognito User Pool Application Client Hosted Login IU URL")
                .value(String.format(
                        "https://%s.auth.%s.amazoncognito.com/login?client_id=%s&response_type=code&redirect_uri=http://localhost",
                        userPoolClientId,
                        Aws.REGION,
                        userPoolClientId))
                .build();

        CfnOutput.Builder.create(this, "CognitoAuthCommand")
                .description("Aws CLI command for Amazon Cognito User Pool authentication")
                .value(String.format(
                        "aws cognito-idp initiate-auth --auth-flow USER_PASSWORD_AUTH --client-id %s --auth-parameters USERNAME=<user@example.com>,PASSWORD=<password>",
                        userPoolClientId))
                .build();
    }

    private Function createFunction(String name, String handler, String codePath) {
        Function lambda = Function.Builder.create(this, name)
                .runtime(Runtime.PYTHON_3_12)
                .handler(handler)
                .code(Code.fromAsset(codePath))
                .build();
        return lambda;
    }
}
