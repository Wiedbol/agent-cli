package com.agent;

public class Agent {
    public String respond(String input) {
        if (input == null || input.isBlank()) {
            return "Hello, Agent!";
        }
        if ("hello".equalsIgnoreCase(input.trim())) {
            return "Hello, Agent!";
        }
        return "I heard: " + input;
    }
}
