package com.seple.ThingsBoard_Bot.model.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChartData {

    private String label;
    private List<DataPoint> points;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DataPoint {
        private long t;  // timestamp
        private String y; // value
    }
}
