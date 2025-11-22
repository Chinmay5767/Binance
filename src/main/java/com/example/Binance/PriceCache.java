package com.example.Binance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

@Service	
public class PriceCache {

	private Map<String , String> priceCache = new ConcurrentHashMap<>();

	public String getPrice(String symbol) {
		return priceCache.get(symbol);
	}

	public void updatePrice(String symbol, String price) {
		priceCache.put(symbol, price);
		
	}
	
}
