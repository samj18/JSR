package com.filebridge.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.filebridge")
@EnableScheduling
public class SpringConfig {
}
