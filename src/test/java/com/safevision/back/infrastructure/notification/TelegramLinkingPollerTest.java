package com.safevision.back.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.application.service.SiteContactService;
import com.safevision.back.infrastructure.config.TelegramProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unitario (sin red real) — mockea el ExchangeFunction del WebClient para
 * verificar el matching de codigo y las respuestas por Telegram sin depender
 * de la Bot API real. El offset es en memoria (ver Javadoc de la clase), asi
 * que no hace falta simularlo entre corridas de poll().
 */
@DisplayName("TelegramLinkingPoller — polling de getUpdates y vinculacion por codigo")
class TelegramLinkingPollerTest {

    private static final String GET_UPDATES_BODY =
            "{\"ok\":true,\"result\":[{\"update_id\":100,\"message\":{\"chat\":{\"id\":555},\"text\":\"123456\"}}]}";
    private static final String NO_UPDATES_BODY = "{\"ok\":true,\"result\":[]}";
    private static final String GET_UPDATES_BODY_START_COMMAND =
            "{\"ok\":true,\"result\":[{\"update_id\":100,\"message\":{\"chat\":{\"id\":555},\"text\":\"/start 123456\"}}]}";
    private static final String GET_UPDATES_BODY_START_COMMAND_WITH_BOTNAME =
            "{\"ok\":true,\"result\":[{\"update_id\":100,\"message\":{\"chat\":{\"id\":555},"
                    + "\"text\":\"/start@safevision_epp_bot 123456\"}}]}";

    private TelegramLinkingPoller buildPoller(SiteContactService service, ExchangeFunction exchangeFunction,
                                               String botToken) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        TelegramProperties properties = new TelegramProperties(botToken, "chat-fallback");
        return new TelegramLinkingPoller(builder, properties, service, "https://api.telegram.org");
    }

    @Test
    @DisplayName("Mensaje con codigo valido -> vincula y responde confirmacion")
    void poll_codigoValido_vinculaYConfirma() {
        SiteContactService service = mock(SiteContactService.class);
        when(service.tryLinkByCode("123456", "555")).thenReturn(Mono.just("Supervisor"));
        List<ClientRequest> captured = new CopyOnWriteArrayList<>();
        ExchangeFunction exchangeFunction = request -> {
            captured.add(request);
            if (request.url().getPath().endsWith("/getUpdates")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(GET_UPDATES_BODY).build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        TelegramLinkingPoller poller = buildPoller(service, exchangeFunction, "bot-token-123");

        poller.poll();

        verify(service).tryLinkByCode("123456", "555");
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).url().toString()).contains("/getUpdates");
        assertThat(captured.get(1).url().toString()).contains("/sendMessage");
    }

    @Test
    @DisplayName("Mensaje '/start <codigo>' (formato documentado) -> extrae el codigo y vincula")
    void poll_comandoStartConCodigo_extraeCodigoYVincula() {
        SiteContactService service = mock(SiteContactService.class);
        when(service.tryLinkByCode("123456", "555")).thenReturn(Mono.just("Supervisor"));
        ExchangeFunction exchangeFunction = request -> {
            if (request.url().getPath().endsWith("/getUpdates")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(GET_UPDATES_BODY_START_COMMAND).build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        TelegramLinkingPoller poller = buildPoller(service, exchangeFunction, "bot-token-123");

        poller.poll();

        verify(service).tryLinkByCode("123456", "555");
    }

    @Test
    @DisplayName("Mensaje '/start@bot <codigo>' (Telegram agrega el @bot) -> tambien extrae el codigo")
    void poll_comandoStartConNombreDeBot_extraeCodigoYVincula() {
        SiteContactService service = mock(SiteContactService.class);
        when(service.tryLinkByCode("123456", "555")).thenReturn(Mono.just("Supervisor"));
        ExchangeFunction exchangeFunction = request -> {
            if (request.url().getPath().endsWith("/getUpdates")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(GET_UPDATES_BODY_START_COMMAND_WITH_BOTNAME).build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        TelegramLinkingPoller poller = buildPoller(service, exchangeFunction, "bot-token-123");

        poller.poll();

        verify(service).tryLinkByCode("123456", "555");
    }

    @Test
    @DisplayName("Mensaje con codigo desconocido -> no vincula, responde con ayuda")
    void poll_codigoDesconocido_respondeAyuda() {
        SiteContactService service = mock(SiteContactService.class);
        when(service.tryLinkByCode(anyString(), anyString())).thenReturn(Mono.empty());
        List<ClientRequest> captured = new CopyOnWriteArrayList<>();
        ExchangeFunction exchangeFunction = request -> {
            captured.add(request);
            if (request.url().getPath().endsWith("/getUpdates")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(GET_UPDATES_BODY).build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        TelegramLinkingPoller poller = buildPoller(service, exchangeFunction, "bot-token-123");

        poller.poll();

        assertThat(captured).hasSize(2);
        assertThat(captured.get(1).url().toString()).contains("/sendMessage");
    }

    @Test
    @DisplayName("Sin bot token configurado -> no hace ninguna llamada HTTP")
    void poll_sinBotToken_noHaceLlamadas() {
        SiteContactService service = mock(SiteContactService.class);
        List<ClientRequest> captured = new CopyOnWriteArrayList<>();
        ExchangeFunction exchangeFunction = request -> {
            captured.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        TelegramLinkingPoller poller = buildPoller(service, exchangeFunction, "");

        poller.poll();

        assertThat(captured).isEmpty();
        verify(service, never()).tryLinkByCode(any(), any());
    }

    @Test
    @DisplayName("getUpdates falla (red caida) -> no propaga excepcion")
    void poll_getUpdatesFalla_noPropagaExcepcion() {
        SiteContactService service = mock(SiteContactService.class);
        ExchangeFunction exchangeFunction = request -> Mono.error(new ConnectException("refused"));
        TelegramLinkingPoller poller = buildPoller(service, exchangeFunction, "bot-token-123");

        assertThat(catchThrowable(poller::poll)).isNull();
    }

    @Test
    @DisplayName("Sin updates nuevos -> no llama al servicio ni responde nada")
    void poll_sinUpdates_noHaceNada() {
        SiteContactService service = mock(SiteContactService.class);
        List<ClientRequest> captured = new CopyOnWriteArrayList<>();
        ExchangeFunction exchangeFunction = request -> {
            captured.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(NO_UPDATES_BODY).build());
        };
        TelegramLinkingPoller poller = buildPoller(service, exchangeFunction, "bot-token-123");

        poller.poll();

        assertThat(captured).hasSize(1);
        verify(service, never()).tryLinkByCode(any(), any());
    }
}
