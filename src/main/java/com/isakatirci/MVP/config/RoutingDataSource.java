package com.isakatirci.MVP.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        DataSourceConfig.DataSourceType currentType = isReadOnly ? DataSourceConfig.DataSourceType.READER : DataSourceConfig.DataSourceType.WRITER;
        log.info("Routing database connection: isReadOnly={}, target={}", isReadOnly, currentType);
        return currentType;
    }
}
