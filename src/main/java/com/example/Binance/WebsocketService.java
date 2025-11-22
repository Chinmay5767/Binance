package com.example.Binance;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;


import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import jakarta.annotation.PostConstruct;

@Service
public class WebsocketService {
    
    private static final Logger logger = (Logger) LoggerFactory.getLogger(WebsocketService.class);
    
    private WebSocketClient webSocketClient;
    private final PriceCache priceCache;
    private final ObjectMapper objectMapper;
    private final NotifyToFutureSevice priceNotificationService;
    
    private boolean isConnected = false;
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();

    @Autowired
    public WebsocketService(PriceCache priceCache, ObjectMapper objectMapper, 
    		NotifyToFutureSevice priceNotificationService) {
        this.priceCache = priceCache;
        this.objectMapper = objectMapper;
        this.priceNotificationService = priceNotificationService;
        initializeWebSocket();
    }

    private void initializeWebSocket() {
        try {
            logger.info("Creating WebSocket connection to Binance...");
            
            webSocketClient = new WebSocketClient(new URI("wss://stream.binance.com:9443/ws")) {

                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    isConnected = true;
                    logger.info("WebSocket connection established successfully");
                    
                 
                    if (!subscribedSymbols.isEmpty()) {
                        logger.info("Resubscribing to {} symbols after reconnect", subscribedSymbols.size());
                        subscribedSymbols.forEach(symbol -> {
                            try {
                                subscribeToSymbol(symbol);
                            } catch (Exception e) {
                                logger.error("Failed to resubscribe to {}", symbol, e);
                            }
                        });
                    }
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JsonNode jsonNode = objectMapper.readTree(message);
                        if (jsonNode.has("s") && jsonNode.has("p")) {
                            String symbol = jsonNode.get("s").asText();
                            String price = jsonNode.get("p").asText();
                            
                            logger.debug("Received price update - Symbol: {}, Price: {}", symbol, price);
                            
                            // Update cache
                            priceCache.updatePrice(symbol, price);
                            
                            // Notify all listeners (SSE clients, waiting requests, etc.)
                            priceNotificationService.notifyPriceReceived(symbol, price);
                            
                        } else {
                            logger.debug("Received non-price message: {}", message);
                        }
                    } catch (JsonProcessingException e) {
                        logger.error("Error parsing WebSocket message", e);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    isConnected = false;
                    logger.warn("WebSocket connection closed. Code: {}, Reason: {}", code, reason);
                    
                    // Attempt reconnection after delay
                    scheduleReconnection();
                }

                @Override
                public void onError(Exception ex) {
                    logger.error("WebSocket error occurred", ex);
                }
            };

            logger.info("Connecting WebSocket...");
            webSocketClient.connect();

        } catch (URISyntaxException e) {
            logger.error("Invalid WebSocket URI", e);
            throw new RuntimeException("Failed to create WebSocket URI", e);
        }
    }

    private void subscribeToSymbol(String symbol) {
        try {
            Map<String, Object> request = Map.of(
                "method", "SUBSCRIBE",
                "params", List.of(symbol.toLowerCase() + "@trade"),
                "id", System.currentTimeMillis()
            );

            String subscriptionMessage = objectMapper.writeValueAsString(request);
            webSocketClient.send(subscriptionMessage);
            logger.debug("Subscribed to symbol: {}", symbol);
            
        } catch (JsonProcessingException e) {
            logger.error("Error creating subscription message for symbol: {}", symbol, e);
        }
    }

    public void subscribe(String symbol) {
        if (!isConnected) {
            logger.warn("WebSocket not connected. Cannot subscribe to: {}", symbol);
            return;
        }

        // Track subscribed symbols
        if (subscribedSymbols.add(symbol)) {
            subscribeToSymbol(symbol);
            logger.info("New subscription to symbol: {}", symbol);
        } else {
            logger.debug("Already subscribed to symbol: {}", symbol);
        }
    }

    private void scheduleReconnection() {
        new Thread(() -> {
            try {
                logger.info("Attempting reconnection in 5 seconds...");
                Thread.sleep(5000);
                if (!isConnected) {
                    logger.info("Reconnecting WebSocket...");
                    initializeWebSocket();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Reconnection thread interrupted", e);
            }
        }).start();
    }

    public boolean isConnected() {
        return isConnected;
    }
    
    public Set<String> getSubscribedSymbols() {
        return new HashSet<>(subscribedSymbols);
    }
}