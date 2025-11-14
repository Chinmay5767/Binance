package com.example.Binance;

import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
public class AppController {

	private final RestTemplate restTemplate = new RestTemplate();
    private static final String BINANCE_URL =
            "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT";
    @GetMapping(value = "/price" , produces = "application/json")
    public AppDTO getPrice() {
        return restTemplate.getForObject(BINANCE_URL, AppDTO.class);
    }
}
