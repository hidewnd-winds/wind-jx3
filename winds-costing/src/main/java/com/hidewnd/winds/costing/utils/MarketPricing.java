package com.hidewnd.winds.costing.utils;

import com.hidewnd.winds.costing.dto.PriceQuote;
import java.util.Comparator;
import java.util.List;

/** 已在上游边界校验的挂单，价格单位为铜。 */
public class MarketPricing {
    public record Listing(long unitPrice, int count) {}

    public static PriceQuote quote(List<Listing> listings, int number) {
        PriceQuote quote = new PriceQuote();
        if (listings.isEmpty()) {
            quote.setStatus(PriceQuote.Status.MISSING);
            return quote;
        }
        List<Listing> sorted = listings.stream().sorted(Comparator.comparingLong(Listing::unitPrice)).toList();
        quote.setUnitPrice(sorted.getFirst().unitPrice());
        int remaining = number;
        long total = 0;
        for (Listing listing : sorted) {
            int purchased = Math.min(remaining, listing.count());
            total = Math.addExact(total, Math.multiplyExact(listing.unitPrice(), purchased));
            remaining -= purchased;
            if (remaining == 0) break;
        }
        // 挂单已全部用尽后，按最后一档价格补足缺口；缺口与补价依据一并返回。
        if (remaining > 0) {
            long lastPrice = sorted.getLast().unitPrice();
            total = Math.addExact(total, Math.multiplyExact(lastPrice, remaining));
            quote.setEstimatedNumber(remaining);
            quote.setEstimatedUnitPrice(lastPrice);
        }
        quote.setValue(total);
        quote.setStatus(remaining > 0 ? PriceQuote.Status.EXTRAPOLATED : PriceQuote.Status.MARKET);
        return quote;
    }
}
