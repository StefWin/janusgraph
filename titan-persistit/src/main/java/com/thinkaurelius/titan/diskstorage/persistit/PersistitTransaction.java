package com.thinkaurelius.titan.diskstorage.persistit;

import static com.thinkaurelius.titan.diskstorage.persistit.PersistitStoreManager.VOLUME_NAME;

import com.persistit.*;
import com.persistit.exception.PersistitException;
import com.persistit.exception.RollbackException;
import com.thinkaurelius.titan.diskstorage.PermanentStorageException;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.common.AbstractStoreTransaction;

import com.thinkaurelius.titan.diskstorage.keycolumnvalue.ConsistencyLevel;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

/**
 * @todo: read this and make sure multiple threads aren't sharing transactions http://akiban.github.com/persistit/javadoc/com/persistit/Transaction.html#_threadManagement
 *
 * @todo: add finalize method
 */
public class PersistitTransaction extends AbstractStoreTransaction {

    /**
     * Temporary hack to get around the private session id constructor
     * @return
     */
    private static SessionId getSessionIdHack() {
        Constructor<SessionId> constructor = null;
        try {
            constructor = SessionId.class.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        constructor.setAccessible(true);
        try {
            return constructor.newInstance();
        } catch (InstantiationException e) {
            throw new AssertionError(e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Temporary hack to get around the private transaction constructor
     * @return
     */
    private static Transaction getTransactionHack(Persistit db, SessionId sid) {
        Constructor constructor = Transaction.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        try {
            return (Transaction) constructor.newInstance(db, sid);
        } catch (InstantiationException e) {
            throw new AssertionError(e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private Persistit db;
    private Transaction tx;
    private SessionId sessionId;

    private Map<String, Exchange> exchangeCache = new HashMap<String, Exchange>();

    public PersistitTransaction(Persistit p, ConsistencyLevel level) throws StorageException {
        super(level);
        db = p;
        sessionId = getSessionIdHack();
        assign();
        tx = db.getTransaction();
        assert sessionId == tx.getSessionId();

        try {
            tx.begin();
        } catch (PersistitException ex) {
            throw new PermanentStorageException(ex);
        }
    }

    private void begin() throws StorageException {
        assign();
        if (!tx.isActive()) {
            try {
                tx.begin();
            } catch (PersistitException ex) {
                throw new PermanentStorageException(ex);
            }
        }
    }

    /**
     * Assigns the session id to the current thread
     */
    public synchronized void assign() {
        db.setSessionId(sessionId);
        if (tx != null) assert sessionId == tx.getSessionId();
    }

    @Override
    public synchronized void abort() throws StorageException {
        if (!tx.isActive()) return;
        
        // Transaction being aborted as already begun; can't begin() it again.
        // begin();

        tx.rollback();
        tx.end();
    }

    @Override
    public synchronized void commit() throws StorageException {
        // Transaction being committed as already begun; can't begin() it again.
        // begin();
        int retries = 3;
        try {
            int i = 0;
            while (true) {
                try {
                    tx.commit();
                    break;
                } catch (RollbackException ex) {
                    if (i++ >= retries) {
                        throw ex;
                    }
                }
            }
        } catch (PersistitException ex) {
            throw new PermanentStorageException(ex);
        } finally {
            tx.end();
        }
    }

    public Exchange getExchange(String treeName) throws StorageException {
        return getExchange(treeName, true);
    }

    public Exchange getExchange(String treeName, Boolean create) throws StorageException {
        Exchange exchange = exchangeCache.get(treeName);
        if (exchange != null) {
            return exchange;
        }
        try {
            assign();
            exchange = db.getExchange(VOLUME_NAME, treeName, create);
            exchangeCache.put(treeName, exchange);
            return exchange;
        } catch (PersistitException ex) {
            throw new PermanentStorageException(ex);
        }
    }

    public void releaseExchange(Exchange exchange) {
        // For now, do not release the Exchange.  This is a workaround for the temporary
        // behavior in which a new SessionId is created for every transaction.  Calling
        // releaseExchange stores an Exchange in a map keyed by a SessionId that will never
        // be used again or removed.
        //
        // db.releaseExchange(exchange);
    }

    public Transaction getTx() {
        return tx;
    }
}
