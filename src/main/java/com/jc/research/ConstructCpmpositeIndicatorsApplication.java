package com.jc.research;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

@SpringBootApplication
@EnableNeo4jRepositories("com.jc.research.mapper")
//@MapperScan("com.jc.research.mapper")
public class ConstructCpmpositeIndicatorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConstructCpmpositeIndicatorsApplication.class, args);
    }

}