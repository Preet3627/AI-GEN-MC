package com.example.ai;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

public class CommandCapture {

    public static class CapturedOutput {
        public String command;
        public String output;

        public CapturedOutput(String command, String output) {
            this.command = command;
            this.output = output;
        }
    }

    public static CapturedOutput executeAndCapture(ServerWorld world, String command) {
        var server = world.getServer();
        if (server == null) return new CapturedOutput(command, "Server unavailable");

        var capturingOutput = new CapturingCommandOutput();
        var source = server.getCommandSource().withOutput(capturingOutput);

        String cmd = command.startsWith("/") ? command.substring(1) : command;
        server.getCommandManager().parseAndExecute(source, cmd);

        return new CapturedOutput(command, capturingOutput.getOutput());
    }

    public static class CapturingCommandOutput implements CommandOutput {
        private final StringBuilder sb = new StringBuilder();

        @Override
        public void sendMessage(Text message) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(message.getString());
        }

        @Override
        public boolean shouldReceiveFeedback() {
            return true;
        }

        @Override
        public boolean shouldTrackOutput() {
            return true;
        }

        @Override
        public boolean shouldBroadcastConsoleToOps() {
            return false;
        }

        @Override
        public boolean cannotBeSilenced() {
            return true;
        }

        public String getOutput() {
            return sb.toString();
        }
    }
}
