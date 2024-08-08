package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Template;

import java.io.IOException;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

public class ChatStackTest {

    @Test
    public void testStack() throws IOException {
        App app = new App();
        ChatStack stack = new ChatStack(app, "test");
        Template template = Template.fromStack(stack);
        template.hasResourceProperties("AWS::Lambda::Function", new HashMap<String,String>(){{
            put("Runtime", "python3.12");
        }});
        template.resourceCountIs("AWS::Lambda::Function", 4);
        template.resourceCountIs("AWS::DynamoDB::Table", 1);
    }
}
