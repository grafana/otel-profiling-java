package io.pyroscope.example;

import org.springframework.stereotype.Service;

@Service
public class CpuBurner {

    public void burnFor(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            // busy spin — generates CPU samples
        }
    }
}
