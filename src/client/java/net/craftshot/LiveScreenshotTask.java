package net.craftshot;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;

public class LiveScreenshotTask {

    private static final int INTERVAL_TICKS = 10;
    private static final String API_URL = "https://craftshot.net/v2/live-frame";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static int tickCounter = 0;
    private static boolean enabled = false;
    private static volatile boolean capturing = false;
    private static volatile long lastFrameCrc = -1;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!enabled) return;
            if (client.level == null) return;
            if (client.player == null) return;
            if (client.gui.screen() instanceof PauseScreen) return;
            if (capturing) return;

            tickCounter++;
            if (tickCounter >= INTERVAL_TICKS) {
                tickCounter = 0;
                capturing = true;
                File liveFile = new File(client.gameDirectory, "screenshots/live.png");

                RenderTarget rt = client.gameRenderer.mainRenderTarget();
                int factor = (rt.width % 2 == 0 && rt.height % 2 == 0) ? 2 : 1;

                Screenshot.grab(client.gameDirectory, "live.png", rt, factor, _ -> {
                    capturing = false;
                    String token = Minecraft.getInstance().getUser().getAccessToken();
                    Thread.ofVirtual().start(() -> uploadFrame(liveFile, token));
                });
            }
        });
    }

    private static void uploadFrame(File file, String token) {
        try {
            byte[] pngBytes = Files.readAllBytes(file.toPath());
            byte[] imageBytes = pngToJpeg(pngBytes);

            CRC32 crc = new CRC32();
            crc.update(imageBytes);
            long currentCrc = crc.getValue();
            if (currentCrc == lastFrameCrc) return;
            lastFrameCrc = currentCrc;
            String boundary = "CraftShotLive-" + System.nanoTime();
            byte[] body = buildMultipart(boundary, token, imageBytes);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(API_URL)).header("Content-Type", "multipart/form-data; boundary=" + boundary).header("User-Agent", "CraftShot-Fabric/1.0").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();

            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
                if (res.statusCode() != 200)
                    System.err.println("[CraftShot] Live frame upload failed: " + res.statusCode() + " " + res.body());
            }).exceptionally(ex -> {
                System.err.println("[CraftShot] Live frame upload error: " + ex.getMessage());
                return null;
            });

        } catch (Exception e) {
            System.err.println("[CraftShot] Could not read live frame file: " + e.getMessage());
        }
    }

    private static byte[] pngToJpeg(byte[] pngBytes) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngBytes));
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality((float) 0.75);
        writer.setOutput(ImageIO.createImageOutputStream(baos));
        writer.write(null, new IIOImage(rgb, null, null), params);
        writer.dispose();
        return baos.toByteArray();
    }

    private static byte[] buildMultipart(String boundary, String token, byte[] imageBytes) {
        String crlf = "\r\n";
        String dash = "--";

        String header = dash + boundary + crlf + "Content-Disposition: form-data; name=\"access_token\"" + crlf + crlf + token + crlf + dash + boundary + crlf + "Content-Disposition: form-data; name=\"frame\"; filename=\"frame.jpg\"" + crlf + "Content-Type: image/jpeg" + crlf + crlf;

        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = (crlf + dash + boundary + dash + crlf).getBytes(StandardCharsets.UTF_8);

        byte[] result = new byte[headerBytes.length + imageBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(imageBytes, 0, result, headerBytes.length, imageBytes.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + imageBytes.length, footerBytes.length);
        return result;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            tickCounter = 0;
            lastFrameCrc = -1;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }
}


