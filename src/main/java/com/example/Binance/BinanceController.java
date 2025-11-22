package com.example.Binance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@RestController
public class BinanceController {
    
    private static final Logger logger = LoggerFactory.getLogger(BinanceController.class);
    
    @Autowired
    private WebsocketService websocketService;
    
    @Autowired
    private PriceCache priceCache;
    
    @Autowired
    private NotifyToFutureSevice priceNotificationService;

    // One-time price request endpoint
    @GetMapping("/{symbol}")
    public CompletableFuture<ResponseEntity<Map<String, String>>> getPrice(@PathVariable String symbol) {
        String pair = symbol.toUpperCase() + "USDT";
        
        logger.info("Fetching price for symbol: {}", pair);
        
        // Check if price is already in cache
        String cachedPrice = priceCache.getPrice(pair);
        if (cachedPrice != null) {
            logger.info("Returning cached price for {}: {}", pair, cachedPrice);
            return CompletableFuture.completedFuture(
                ResponseEntity.ok(Map.of("symbol", pair, "price", cachedPrice))
            );
        }
        
        // Check if WebSocket is connected
        if (!websocketService.isConnected()) {
            logger.error("WebSocket is not connected");
            return CompletableFuture.completedFuture(
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "WebSocket service unavailable", "symbol", pair))
            );
        }
        
        // Subscribe to WebSocket first
        websocketService.subscribe(pair);
        
        // Wait for price with timeout
        return priceNotificationService.waitForPrice(pair)
                .thenApply(price -> {
                    logger.info("Successfully returning price for {}: {}", pair, price);
                    return ResponseEntity.ok(Map.of("symbol", pair, "price", price));
                })
                .exceptionally(throwable -> {
                    logger.warn("Failed to get price for {}: {}", pair, throwable.getMessage());
                    return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                            .body(Map.of("error", "Price not received within timeout period", "symbol", pair));
                });
    }

    // Real-time streaming endpoint with Server-Sent Events
    @GetMapping(value = "/{symbol}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamPrices(@PathVariable String symbol) {
        String pair = symbol.toUpperCase() + "USDT";
        
        logger.info("Starting real-time stream for symbol: {}", pair);
        
        // Subscribe to WebSocket for this symbol
        websocketService.subscribe(pair);
        
        return Flux.create(sink -> {
            // Create listener that forwards data to the SSE sink
            Consumer<String> priceListener = (priceData) -> {
                try {
                    sink.next(priceData);
                } catch (Exception e) {
                    logger.error("Error sending data to SSE stream for {}", pair, e);
                }
            };
            
            // Register the listener - will be called on every price update
            priceNotificationService.addPriceListener(pair, priceListener);
            
            logger.info("SSE stream started for {}. Client connected.", pair);
            
            // Send initial price immediately if available
            String currentPrice = priceCache.getPrice(pair);
            if (currentPrice != null) {
                String initialData = String.format("data:{\"symbol\":\"%s\",\"price\":\"%s\",\"type\":\"initial\",\"timestamp\":%d}\n\n", 
                    pair, currentPrice, System.currentTimeMillis());
                sink.next(initialData);
                logger.debug("Sent initial price for {}: {}", pair, currentPrice);
            }
            
            // Start heartbeat to keep connection alive
            Disposable heartbeatDisposable = Flux.interval(Duration.ofSeconds(30))
                .subscribe(tick -> {
                    try {
                        String heartbeat = String.format("data:{\"type\":\"heartbeat\",\"timestamp\":%d}\n\n", 
                            System.currentTimeMillis());
                        sink.next(heartbeat);
                        logger.debug("Sent heartbeat for {}", pair);
                    } catch (Exception e) {
                        logger.error("Error sending heartbeat for {}", pair, e);
                    }
                });
            
            // Cleanup when client disconnects or stream completes
            sink.onDispose(() -> {
                // Remove the listener so we stop sending updates to this client
                priceNotificationService.removePriceListener(pair, priceListener);
                
                // Stop the heartbeat
                heartbeatDisposable.dispose();
                
                logger.info("SSE stream ended for {}. Client disconnected.", pair);
            });
            
            // Handle cancellation
            sink.onCancel(() -> {
                priceNotificationService.removePriceListener(pair, priceListener);
                heartbeatDisposable.dispose();
                logger.info("SSE stream cancelled for {}", pair);
            });
            
          
        });
    }

    // Get multiple symbols at once
    @PostMapping("/batch")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getBatchPrices(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            java.util.List<String> symbols = (java.util.List<String>) request.get("symbols");
            
            if (symbols == null || symbols.isEmpty()) {
                return CompletableFuture.completedFuture(
                    ResponseEntity.badRequest()
                        .body(Map.of("error", "No symbols provided"))
                );
            }
            
            // Create futures for all symbols
            Map<String, CompletableFuture<String>> priceFutures = new ConcurrentHashMap<>();
            
            symbols.forEach(symbol -> {
                String pair = symbol.toUpperCase() + "USDT";
                
                // Check cache first
                String cachedPrice = priceCache.getPrice(pair);
                if (cachedPrice != null) {
                    priceFutures.put(symbol, CompletableFuture.completedFuture(cachedPrice));
                } else {
                    // Subscribe and wait for price
                    websocketService.subscribe(pair);
                    priceFutures.put(symbol, priceNotificationService.waitForPrice(pair));
                }
            });
            
            // Wait for all futures to complete
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                priceFutures.values().toArray(new CompletableFuture[0])
            );
            
            return allFutures.thenApply(voi -> {
                Map<String, String> results = new java.util.HashMap<>();
                priceFutures.forEach((symbol, future) -> {
                    try {
                        String price = future.get(1, java.util.concurrent.TimeUnit.SECONDS); // Short timeout since we already waited
                        results.put(symbol, price != null ? price : "N/A");
                    } catch (Exception e) {
                        results.put(symbol, "ERROR");
                    }
                });
                
                return ResponseEntity.ok(Map.of(
                    "prices", results,
                    "timestamp", System.currentTimeMillis()
                ));
            });
            
        } catch (Exception e) {
            logger.error("Error processing batch request", e);
            return CompletableFuture.completedFuture(
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"))
            );
        }
    }

    // Status endpoint to check WebSocket connection
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
            "websocketConnected", websocketService.isConnected(),
            "subscribedSymbols", websocketService.getSubscribedSymbols(),
            "timestamp", System.currentTimeMillis(),
            "service", "Binance Price API"
        ));
    }

    // Health check endpoint
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "Binance Price API",
            "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }
}