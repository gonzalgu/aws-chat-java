package com.myorg;

import software.amazon.awscdk.App;

public final class ChatApp {
    public static void main(final String[] args) {
        App app = new App();

        new ChatStack(app, "ChatStack");

        app.synth();
    }
}
