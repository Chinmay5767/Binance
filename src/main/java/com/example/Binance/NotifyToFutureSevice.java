package com.example.Binance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

@Component
public class NotifyToFutureSevice {
    
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> priceListeners = new ConcurrentHashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(NotifyToFutureSevice.class);

    // For one-time price requests
    public  CompletableFuture<String> waitForPrice(String symbol) {
        cancelRequest(symbol); // Clean up any existing
        
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(symbol, future);
        
        return future.orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((result, throwable) -> {
                    pendingRequests.remove(symbol);
                    if (throwable instanceof TimeoutException) {
                        logger.warn("Timeout waiting for price: {}", symbol);
                    }
                });
    }

    // For real-time streaming
    public void addPriceListener(String symbol, Consumer<String> listener) {
        priceListeners.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>())
                     .add(listener);
        logger.debug("Added listener for {}. Total: {}", symbol, 
                    priceListeners.get(symbol).size());
    }
    
    public void removePriceListener(String symbol, Consumer<String> listener) {
        List<Consumer<String>> listeners = priceListeners.get(symbol);
        if (listeners != null) {
            listeners.remove(listener);
            logger.debug("Removed listener for {}. Remaining: {}", symbol, listeners.size());
            
            if (listeners.isEmpty()) {
                priceListeners.remove(symbol);
            }
        }
    }

    public void notifyPriceReceived(String symbol, String price) {
        logger.debug("Notifying price received for {}: {}", symbol, price);
        
        // 1. Notify one-time requests
        CompletableFuture<String> future = pendingRequests.get(symbol);
        if (future != null && !future.isDone()) {
            future.complete(price);
        }
        
        // 2. Notify continuous listeners (SSE clients)
        notifyAllListeners(symbol, price);
    }
    
    private void notifyAllListeners(String symbol, String price) {
        List<Consumer<String>> listeners = priceListeners.get(symbol);
        if (listeners != null && !listeners.isEmpty()) {
            String priceData = String.format("data:{\"symbol\":\"%s\",\"price\":\"%s\",\"timestamp\":%d}\n\n", 
                    symbol, price, System.currentTimeMillis());
            
            listeners.forEach(listener -> {
                try {
                    listener.accept(priceData);
                } catch (Exception e) {
                    logger.error("Error notifying listener for {}", symbol, e);
                }
            });
        }
    }

    public void cancelRequest(String symbol) {
        CompletableFuture<String> future = pendingRequests.remove(symbol);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }
}