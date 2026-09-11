package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class Main {

    private static final String QUIT_COMMAND = "quit";

    public static void main(String[] args) throws IOException {
        Agent agent = new Agent();
        BufferedReader reader
                = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            if (QUIT_COMMAND.equalsIgnoreCase(line.trim())) {
                break;
            }
            System.out.println(agent.respond(line));
        }
    }
}
