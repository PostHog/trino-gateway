/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.config;

import io.airlift.units.Duration;

public class DataStoreConfiguration
{
    private String jdbcUrl;
    private String user;
    private String password;
    private String driver;
    private boolean queryHistoryEnabled = true;
    private Integer queryHistoryHoursRetention = 4;
    private Integer queryHistoryMaxQueryTextLength;
    private boolean runMigrationsEnabled = true;
    private boolean connectionPoolEnabled;
    private int maximumPoolSize = 4;
    private int connectionAcquisitionTimeoutMillis = 1000;
    private int connectTimeoutSeconds = 2;
    private int socketTimeoutSeconds = 10;
    private int statementTimeoutMillis = 5000;
    private int lockTimeoutMillis = 250;
    // How long an OAuth2 pin is kept before the periodic DB cleanup may prune it. Pins only need to
    // outlive an in-flight handshake (minutes), so an hour is ample. Note this is a lower bound: the
    // cleanup sweep runs on its own fixed cadence, so a pin can linger until the next sweep after this
    // period elapses.
    private Duration oauth2RoutingRetention = Duration.valueOf("1h");
    private Integer maxPoolSize;
    private Duration keepaliveTime;
    private Duration maxLifetime;

    public DataStoreConfiguration(
            String jdbcUrl,
            String user,
            String password,
            String driver,
            boolean queryHistoryEnabled,
            Integer queryHistoryHoursRetention,
            boolean runMigrationsEnabled,
            Integer maxPoolSize)
    {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.driver = driver;
        this.queryHistoryEnabled = queryHistoryEnabled;
        this.queryHistoryHoursRetention = queryHistoryHoursRetention;
        this.runMigrationsEnabled = runMigrationsEnabled;
        this.maxPoolSize = maxPoolSize;
    }

    public DataStoreConfiguration() {}

    public boolean isConnectionPoolEnabled()
    {
        return connectionPoolEnabled;
    }

    public void setConnectionPoolEnabled(boolean connectionPoolEnabled)
    {
        this.connectionPoolEnabled = connectionPoolEnabled;
    }

    public int getMaximumPoolSize()
    {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize)
    {
        this.maximumPoolSize = maximumPoolSize;
    }

    public int getConnectionAcquisitionTimeoutMillis()
    {
        return connectionAcquisitionTimeoutMillis;
    }

    public void setConnectionAcquisitionTimeoutMillis(int connectionAcquisitionTimeoutMillis)
    {
        this.connectionAcquisitionTimeoutMillis = connectionAcquisitionTimeoutMillis;
    }

    public int getConnectTimeoutSeconds()
    {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds)
    {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getSocketTimeoutSeconds()
    {
        return socketTimeoutSeconds;
    }

    public void setSocketTimeoutSeconds(int socketTimeoutSeconds)
    {
        this.socketTimeoutSeconds = socketTimeoutSeconds;
    }

    public int getStatementTimeoutMillis()
    {
        return statementTimeoutMillis;
    }

    public void setStatementTimeoutMillis(int statementTimeoutMillis)
    {
        this.statementTimeoutMillis = statementTimeoutMillis;
    }

    public int getLockTimeoutMillis()
    {
        return lockTimeoutMillis;
    }

    public void setLockTimeoutMillis(int lockTimeoutMillis)
    {
        this.lockTimeoutMillis = lockTimeoutMillis;
    }

    public String getJdbcUrl()
    {
        return this.jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl)
    {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUser()
    {
        return this.user;
    }

    public void setUser(String user)
    {
        this.user = user;
    }

    public String getPassword()
    {
        return this.password;
    }

    public void setPassword(String password)
    {
        this.password = password;
    }

    public String getDriver()
    {
        return this.driver;
    }

    public void setDriver(String driver)
    {
        this.driver = driver;
    }

    public boolean isQueryHistoryEnabled()
    {
        return queryHistoryEnabled;
    }

    public void setQueryHistoryEnabled(boolean queryHistoryEnabled)
    {
        this.queryHistoryEnabled = queryHistoryEnabled;
    }

    public Integer getQueryHistoryHoursRetention()
    {
        return this.queryHistoryHoursRetention;
    }

    public void setQueryHistoryHoursRetention(Integer queryHistoryHoursRetention)
    {
        this.queryHistoryHoursRetention = queryHistoryHoursRetention;
    }

    public Integer getQueryHistoryMaxQueryTextLength()
    {
        return this.queryHistoryMaxQueryTextLength;
    }

    public void setQueryHistoryMaxQueryTextLength(Integer queryHistoryMaxQueryTextLength)
    {
        this.queryHistoryMaxQueryTextLength = queryHistoryMaxQueryTextLength;
    }

    public boolean isRunMigrationsEnabled()
    {
        return this.runMigrationsEnabled;
    }

    public void setRunMigrationsEnabled(boolean runMigrationsEnabled)
    {
        this.runMigrationsEnabled = runMigrationsEnabled;
    }

    public Duration getOauth2RoutingRetention()
    {
        return this.oauth2RoutingRetention;
    }

    public void setOauth2RoutingRetention(String oauth2RoutingRetention)
    {
        this.oauth2RoutingRetention = Duration.valueOf(oauth2RoutingRetention);
    }

    public Integer getMaxPoolSize()
    {
        return maxPoolSize;
    }

    public void setMaxPoolSize(Integer maxPoolSize)
    {
        this.maxPoolSize = maxPoolSize;
    }

    public Duration getKeepaliveTime()
    {
        return keepaliveTime;
    }

    public void setKeepaliveTime(Duration keepaliveTime)
    {
        this.keepaliveTime = keepaliveTime;
    }

    public Duration getMaxLifetime()
    {
        return maxLifetime;
    }

    public void setMaxLifetime(Duration maxLifetime)
    {
        this.maxLifetime = maxLifetime;
    }
}
