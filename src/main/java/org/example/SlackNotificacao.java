package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SlackNotificacao {

    private static final String WEBHOOK_URL = "SLACK_WEBHOOK_URL";
    private static final int PORTA = 8080;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORTA), 0);

        server.createContext("/notificar", SlackNotificacao::tratarRequisicao);
        server.setExecutor(null);
        server.start();

        System.out.println("API NotificacaoAircode rodando na porta " + PORTA + " (POST /notificar)");
    }

    private static void tratarRequisicao(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            enviarResposta(exchange, 405, "{\"erro\": \"Metodo nao permitido. Use POST.\"}");
            return;
        }

        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            String nomeAlerta = extrairCampoJson(body, "nome");
            String ancora = extrairCampoJson(body, "ancora");
            String mensagemUsuario = extrairCampoJson(body, "mensagem");
            String dataHora = extrairCampoJson(body, "dataHora");

            if (dataHora.isEmpty()) {
                dataHora = LocalDateTime.now(ZoneId.of("America/Sao_Paulo"))
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
            }

            if (mensagemUsuario.isEmpty()) {
                enviarResposta(exchange, 400, "{\"erro\": \"Campo 'mensagem' e obrigatorio.\"}");
                return;
            }

            String textoFinalSlack = String.format(
                    "%s - O Alerta '%s' (Âncora: %s) foi atingido! Mensagem: %s",
                    dataHora,
                    nomeAlerta.isEmpty() ? "Aircode" : nomeAlerta,
                    ancora.isEmpty() ? "-" : ancora,
                    mensagemUsuario
            );

            boolean enviado = dispararFluxoSlack(WEBHOOK_URL, textoFinalSlack);

            if (enviado) {
                enviarResposta(exchange, 200, "{\"status\": \"Notificacao enviada ao Slack com sucesso!\"}");
            } else {
                enviarResposta(exchange, 502, "{\"erro\": \"Falha ao repassar notificacao para o Webhook do Slack.\"}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            enviarResposta(exchange, 500, "{\"erro\": \"Erro interno na API Java.\"}");
        }
    }

    public static boolean dispararFluxoSlack(String webhookUrl, String valorVariavel) {
        String escapado = valorVariavel
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");

        String jsonPayload = String.format("{\"text\": \"%s\"}", escapado);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("Fluxo acionado e mensagem enviada ao Slack: " + valorVariavel);
                return true;
            } else {
                System.err.println("Erro HTTP Slack: " + response.statusCode() + " - " + response.body());
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static String extrairCampoJson(String json, String chave) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(chave) + "\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        }
        return "";
    }

    private static void enviarResposta(HttpExchange exchange, int statusCode, String jsonResposta) throws IOException {
        byte[] bytes = jsonResposta.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}