package lerna.nablarch.batch.parallelizable.testkit;

import nablarch.core.db.connection.TransactionManagerConnection;
import nablarch.core.db.dialect.Dialect;
import nablarch.core.db.statement.*;
import nablarch.core.db.transaction.JdbcTransactionTimeoutHandler;

import java.sql.Connection;


public class TestTransactionManagerConnectionBase implements TransactionManagerConnection {

    @Override
    public void initialize() {
        // Do nothing
    }

    @Override
    public void commit() {
        // Do nothing
    }

    @Override
    public void rollback() {
        // Do nothing
    }

    @Override
    public void terminate() {
        // Do nothing
    }

    @Override
    public void setIsolationLevel(int i) {
        // Do nothing
    }

    @Override
    public void setJdbcTransactionTimeoutHandler(JdbcTransactionTimeoutHandler jdbcTransactionTimeoutHandler) {
        // Do nothing
    }

    @Override
    public Connection getConnection() {
        return null;
    }

    @Override
    public Dialect getDialect() {
        return null;
    }

    @Override
    public void removeStatement(SqlStatement sqlStatement) {
        // Do nothing
    }

    @Override
    public SqlPStatement prepareStatement(String s) {
        return null;
    }

    @Override
    public SqlPStatement prepareStatement(String s, SelectOption selectOption) {
        return null;
    }

    @Override
    public SqlPStatement prepareStatement(String s, int i) {
        return null;
    }

    @Override
    public SqlPStatement prepareStatement(String s, int[] ints) {
        return null;
    }

    @Override
    public SqlPStatement prepareStatement(String s, String[] strings) {
        return null;
    }

    @Override
    public SqlPStatement prepareStatementBySqlId(String s) {
        return null;
    }

    @Override
    public SqlPStatement prepareStatementBySqlId(String s, SelectOption selectOption) {
        return null;
    }

    @Override
    public ParameterizedSqlPStatement prepareParameterizedSqlStatement(String s) {
        return null;
    }

    @Override
    public ParameterizedSqlPStatement prepareParameterizedSqlStatement(String s, SelectOption selectOption) {
        return null;
    }

    @Override
    public ParameterizedSqlPStatement prepareParameterizedSqlStatementBySqlId(String s) {
        return null;
    }

    @Override
    public ParameterizedSqlPStatement prepareParameterizedSqlStatementBySqlId(String s, SelectOption selectOption) {
        return null;
    }

    @Override
    public ParameterizedSqlPStatement prepareParameterizedSqlStatement(String s, Object o) {
        return null;
    }

    @Override
    public ParameterizedSqlPStatement prepareParameterizedSqlStatement(String s, Object o, SelectOption selectOption) {
        return null;
    }

    @Override
    public ParameterizedSqlPStatement prepareParameterizedSqlStatementBySqlId(String s, Object o) {
        return null;
    }

    @Override
    public ParameterizedSqlPStatement prepareParameterizedSqlStatementBySqlId(String s, Object o, SelectOption selectOption) {
        return null;
    }

    @Override
    public ParameterizedSqlPStatement prepareParameterizedCountSqlStatementBySqlId(String s, Object o) {
        return null;
    }

    @Override
    public SqlPStatement prepareCountStatementBySqlId(String s) {
        return null;
    }

    @Override
    public SqlCStatement prepareCall(String s) {
        return null;
    }

    @Override
    public SqlCStatement prepareCallBySqlId(String s) {
        return null;
    }
}
