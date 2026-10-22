package io.openmesh.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Keeps every gateway instance's routing table in sync.
 * A route change publishes a message on a Redis Pub/Sub channel; each instance listens and
 * reloads its routes. Without this, only the instance that handled the admin call would reload.
 */
@Slf4j
@Component
public class RouteRefreshBroadcaster implements DisposableBean {

    public static final String CHANNEL = "openmesh:routes:refresh";

    private final ReactiveRedisConnectionFactory connectionFactory;
    private final ReactiveStringRedisTemplate redis;
    private final ApplicationEventPublisher events;

    private volatile ReactiveRedisMessageListenerContainer container;
    private volatile Disposable subscription;

    public RouteRefreshBroadcaster(ReactiveRedisConnectionFactory connectionFactory,
                                   ReactiveStringRedisTemplate redis,
                                   ApplicationEventPublisher events) {
        this.connectionFactory = connectionFactory;
        this.redis = redis;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        subscription = Flux.defer(() -> {
                    container = new ReactiveRedisMessageListenerContainer(connectionFactory);
                    return container.receive(ChannelTopic.of(CHANNEL));
                })
                .doOnNext(message -> {
                    log.info("Route change broadcast received; reloading routes");
                    refreshLocally();
                })
                .doOnError(e -> log.warn("Route refresh subscription lost ({}); retrying", e.toString()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
                .subscribe();
    }

    /** Reloads this instance immediately and tells all other instances to do the same. */
    public Mono<Void> broadcast() {
        refreshLocally();
        return redis.convertAndSend(CHANNEL, "refresh")
                .doOnNext(receivers -> log.debug("Route refresh broadcast delivered to {} instance(s)", receivers))
                .onErrorResume(e -> {
                    log.warn("Could not broadcast route refresh ({}); other instances reload on next change", e.toString());
                    return Mono.empty();
                })
                .then();
    }

    public void refreshLocally() {
        events.publishEvent(new RefreshRoutesEvent(this));
    }

    @Override
    public void destroy() {
        if (subscription != null) {
            subscription.dispose();
        }
        if (container != null) {
            container.destroy();
        }
    }
}
