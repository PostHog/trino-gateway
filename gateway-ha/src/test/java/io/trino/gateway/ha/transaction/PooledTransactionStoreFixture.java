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
package io.trino.gateway.ha.transaction;

import io.trino.gateway.ha.transaction.PoolStore.PoolException;
import io.trino.gateway.ha.transaction.TransactionStore.Admission;
import io.trino.gateway.ha.transaction.TransactionStore.BackendRef;
import io.trino.gateway.ha.transaction.TransactionStore.DrainStatus;
import io.trino.gateway.ha.transaction.TransactionStore.ErrorCode;
import io.trino.gateway.ha.transaction.TransactionStore.QueryBinding;
import io.trino.gateway.ha.transaction.TransactionStore.ResponseObservation;
import io.trino.gateway.ha.transaction.TransactionStore.StoreException;
import io.trino.gateway.ha.transaction.TransactionStore.TransactionBinding;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Test-only compatibility vocabulary for core ledger tests. Every fixture backend is a certified
 * pooled member; admissions and lifecycle transitions use the production pooled protocol rather
 * than resurrecting the removed non-pooled administration paths.
 */
public final class PooledTransactionStoreFixture
{
    private final TransactionStore transactions;
    private final PoolStore pools;

    public PooledTransactionStoreFixture(Jdbi database)
    {
        transactions = new TransactionStore(database);
        pools = new PoolStore(database);
    }

    public BackendRef ensureBackend(String name, String url, String externalUrl, String pool, String node, String coordinator)
    {
        if (pools.poolState(pool).isEmpty()) {
            pools.configurePool(pool, guard(), new PoolStore.PoolSpec("POOLED", 1, 4, 1, 1, "fixture-revision", false), false);
        }
        pools.registerMember(pool, guard(), new PoolStore.MemberRegistration(
                name, name, url, externalUrl, "pod-" + name, "boot-" + name, "fixture-revision", null, node, coordinator));
        var member = pools.member(pool, name).orElseThrow();
        pools.admitMember(pool, name, guard(), member.generation(), new PoolStore.ValidationReceipt(
                "a".repeat(64),
                "fixture-revision",
                "fixture-auth",
                "pod-" + name,
                "boot-" + name,
                node,
                coordinator,
                1,
                List.of("image", "workers", "catalog-revision", "auth-revision", "operational-connection")), node, coordinator, 300);
        return transactions.getBackend(name).orElseThrow();
    }

    public Admission admitNew(String name, String owner, String pool)
    {
        return transactions.admitPooledMember(pool, name, owner);
    }

    public DrainStatus beginDrain(String name)
    {
        BackendRef backend = transactions.getBackend(name).orElseThrow();
        var member = pools.member(backend.poolId(), name).orElseThrow();
        pools.drainMember(backend.poolId(), name, guard(), member.generation());
        return transactions.drainStatus(name);
    }

    public DrainStatus seal(String name, long generation)
    {
        BackendRef backend = transactions.getBackend(name).orElseThrow();
        return translated(() -> {
            pools.sealMember(backend.poolId(), name, guard(), generation);
            return transactions.drainStatus(name);
        });
    }

    public DrainStatus drainStatus(String name)
    {
        return transactions.drainStatus(name);
    }

    public Optional<BackendRef> getBackend(String name)
    {
        return transactions.getBackend(name);
    }

    public Optional<QueryBinding> getQuery(String query)
    {
        return transactions.getQuery(query);
    }

    public Optional<TransactionBinding> getTransaction(String transaction)
    {
        return transactions.getTransaction(transaction);
    }

    public Admission admitTransaction(String transaction, String owner)
    {
        return transactions.admitTransaction(transaction, owner);
    }

    public Admission admitQuery(String query, Optional<String> owner, Optional<String> transaction)
    {
        return transactions.admitQuery(query, owner, transaction);
    }

    public Admission admitQuery(String query, Optional<String> owner, Optional<String> transaction, Optional<String> capability)
    {
        return transactions.admitQuery(query, owner, transaction, capability);
    }

    public void recordResponse(UUID admission, ResponseObservation observation)
    {
        transactions.recordResponse(admission, observation);
    }

    public void markUncertain(UUID admission)
    {
        transactions.markUncertain(admission);
    }

    public void rejectAdmission(UUID admission)
    {
        transactions.rejectAdmission(admission);
    }

    private static PoolStore.Guard guard()
    {
        return new PoolStore.Guard(UUID.randomUUID().toString(), "fixture-step", 1, "a".repeat(64));
    }

    private static <T> T translated(Supplier<T> operation)
    {
        try {
            return operation.get();
        }
        catch (PoolException failure) {
            ErrorCode code = switch (failure.code()) {
                case POOL_NOT_DRAINED -> ErrorCode.NOT_DRAINED;
                case POOL_STALE_GENERATION -> ErrorCode.STALE_GENERATION;
                default -> ErrorCode.CONFLICT;
            };
            throw new StoreException(code, failure.getMessage());
        }
    }
}
