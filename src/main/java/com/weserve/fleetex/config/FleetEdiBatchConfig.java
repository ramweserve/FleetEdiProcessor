package com.weserve.fleetex.config;

import com.weserve.fleetex.model.FleetEdi;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.BeanPropertyItemSqlParameterSourceProvider;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class FleetEdiBatchConfig {

    @Bean
    @StepScope
    public FlatFileItemReader<FleetEdi> reader(
            @Value("#{jobParameters['filePath']}") String filePath,
            @Value("#{jobParameters['fieldOrder']}") String fieldOrder) {
        
        String[] names;
        if (fieldOrder != null && !fieldOrder.isEmpty()) {
            names = fieldOrder.split(",");
        } else {
            names = new String[]{"equipmentId", "isoCode", "category", "size", "type", "tare", "maxWeight", "status", "remarks", "year"};
        }

        return new FlatFileItemReaderBuilder<FleetEdi>()
                .name("fleetEdiReader")
                .resource(new FileSystemResource(filePath))
                .delimited()
                .names(names)
                .fieldSetMapper(new BeanWrapperFieldSetMapper<>() {{
                    setTargetType(FleetEdi.class);
                }})
                .build();
    }

    @Bean
    @StepScope
    public JdbcBatchItemWriter<FleetEdi> writer(
            DataSource dataSource,
            @Value("#{jobParameters['fieldOrder']}") String fieldOrder,
            @Value("#{jobParameters['tableName']}") String tableName) {
        
        String[] fields;
        if (fieldOrder != null && !fieldOrder.isEmpty()) {
            fields = fieldOrder.split(",");
        } else {
            fields = new String[]{"equipmentId", "isoCode", "category", "size", "type", "tare", "maxWeight", "status", "remarks", "year"};
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName != null ? tableName : "fleet_edi");
        sql.append(" (");
        
        StringBuilder values = new StringBuilder("VALUES (");
        
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i];
            String column = getColumnName(field);
            if (column != null) {
                sql.append(column);
                values.append(":").append(field);
                if (i < fields.length - 1) {
                    sql.append(", ");
                    values.append(", ");
                }
            }
        }
        
        // Remove trailing comma/space if any column was null
        if (sql.toString().endsWith(", ")) {
            sql.setLength(sql.length() - 2);
            values.setLength(values.length() - 2);
        }
        
        sql.append(") ");
        values.append(")");
        sql.append(values);

        return new JdbcBatchItemWriterBuilder<FleetEdi>()
                .itemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider<>())
                .sql(sql.toString())
                .dataSource(dataSource)
                .build();
    }

    private String getColumnName(String fieldName) {
        switch (fieldName) {
            case "equipmentId": return "containerNbr";
            case "isoCode": return "typeIso";
            case "category": return "cargoType";
            case "size": return "length";
            case "type": return "variant";
            case "tare": return "tarewt";
            case "maxWeight": return "safewt";
            case "status": return "code";
            case "remarks": return "reserve";
            case "year": return "year";
            default: return null;
        }
    }

    @Bean
    public Job importFleetEdiJob(JobRepository jobRepository, Step step1) {
        return new JobBuilder("importFleetEdiJob", jobRepository)
                .start(step1)
                .build();
    }

    @Bean
    public Step step1(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                      FlatFileItemReader<FleetEdi> reader, JdbcBatchItemWriter<FleetEdi> writer) {
        return new StepBuilder("step1", jobRepository)
                .<FleetEdi, FleetEdi>chunk(1000, transactionManager)
                .reader(reader)
                .writer(writer)
                .build();
    }
}
