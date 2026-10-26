package com.example;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import javax.imageio.ImageIO;

public class AiClient {
    private static final String KEY = System.getenv("REMOVEBG_KEY");

    public static BufferedImage removeBackground(BufferedImage img) throws Exception {
        if (KEY == null) throw new IllegalStateException("REMOVEBG_KEY not set");

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);

        String boundary = "----Boundary" + System.currentTimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"image_file\"; filename=\"in.png\"\r\n"
            + "Content-Type: image/png\r\n\r\n").getBytes());
        body.write(png.toByteArray());
        body.write(("\r\n--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"size\"\r\n\r\nauto\r\n"
            + "--" + boundary + "--\r\n").getBytes());

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.remove.bg/v1.0/removebg"))
            .header("X-Api-Key", KEY)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();

        HttpResponse<byte[]> res = HttpClient.newHttpClient()
            .send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200)
    throw new IOException("API returned " + res.statusCode()
        + ": " + new String(res.body()));
        return ImageIO.read(new ByteArrayInputStream(res.body()));
    }
}
