package org.springframework.data.jdbc;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.data.domain.*;
import org.springframework.data.jdbc.sql.SqlGenerator;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link PagingAndSortingRepository} using {@link JdbcTemplate}
 */
public abstract class AbstractJdbcRepository<T extends Persistable<ID>, ID extends Serializable> implements PagingAndSortingRepository<T, ID>, InitializingBean, BeanFactoryAware {

	private final TableDescription table;

	private final RowMapper<T> rowMapper;
	private final RowUnmapper<T> rowUnmapper;

	private SqlGenerator sqlGenerator;
	private BeanFactory beanFactory;
	private JdbcOperations jdbcOperations;

	public AbstractJdbcRepository(RowMapper<T> rowMapper, RowUnmapper<T> rowUnmapper, SqlGenerator sqlGenerator, TableDescription table) {
		Assert.notNull(rowMapper);
		Assert.notNull(rowUnmapper);
		Assert.notNull(table);

		this.rowUnmapper = rowUnmapper;
		this.rowMapper = rowMapper;
		this.sqlGenerator = sqlGenerator;
		this.table = table;
	}

	public AbstractJdbcRepository(RowMapper<T> rowMapper, RowUnmapper<T> rowUnmapper, TableDescription table) {
		this(rowMapper, rowUnmapper, null, table);
	}

	public AbstractJdbcRepository(RowMapper<T> rowMapper, RowUnmapper<T> rowUnmapper, String tableName, String idColumn) {
		this(rowMapper, rowUnmapper, null, new TableDescription(tableName, idColumn));
	}

	public AbstractJdbcRepository(RowMapper<T> rowMapper, RowUnmapper<T> rowUnmapper, String tableName) {
		this(rowMapper, rowUnmapper, new TableDescription(tableName, "id"));
	}

	public AbstractJdbcRepository(RowMapper<T> rowMapper, TableDescription table) {
		this(rowMapper, new MissingRowUnmapper<T>(), null, table);
	}

	public AbstractJdbcRepository(RowMapper<T> rowMapper, String tableName, String idColumn) {
		this(rowMapper, new MissingRowUnmapper<T>(), null, new TableDescription(tableName, idColumn));
	}

	public AbstractJdbcRepository(RowMapper<T> rowMapper, String tableName) {
		this(rowMapper, new MissingRowUnmapper<T>(), new TableDescription(tableName, "id"));
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		obtainJdbcTemplate();
		obtainSqlGenerator();
	}

	protected JdbcOperations getJdbcOperations() {
		return jdbcOperations;
	}

	protected TableDescription getTable() {
		return table;
	}

	private void obtainSqlGenerator() {
		try {
			sqlGenerator = beanFactory.getBean(SqlGenerator.class);
		} catch (NoSuchBeanDefinitionException e) {
			sqlGenerator = new SqlGenerator();
		}
	}

	private void obtainJdbcTemplate() {
		try {
			jdbcOperations = beanFactory.getBean(JdbcOperations.class);
		} catch (NoSuchBeanDefinitionException e) {
			final DataSource dataSource = beanFactory.getBean(DataSource.class);
			jdbcOperations = new JdbcTemplate(dataSource);
		}
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public long count() {
		return jdbcOperations.queryForLong(sqlGenerator.count(table));
	}

	@Override
	public void delete(ID id) {
		jdbcOperations.update(sqlGenerator.deleteById(table), id);
	}

	@Override
	public void delete(T entity) {
		jdbcOperations.update(sqlGenerator.deleteById(table), entity.getId());
	}

	@Override
	public void delete(Iterable<? extends T> entities) {
		for (T t : entities) {
			delete(t);
		}
	}

	@Override
	public void deleteAll() {
		jdbcOperations.update(sqlGenerator.deleteAll(table));
	}

	@Override
	public boolean exists(ID id) {
		return jdbcOperations.queryForInt(sqlGenerator.countById(table), id) > 0;
	}

	@Override
	public Iterable<T> findAll() {
		return jdbcOperations.query(sqlGenerator.selectAll(table), rowMapper);
	}

	@Override
	public T findOne(ID id) {
		List<T> entityOrEmpty = jdbcOperations.query(sqlGenerator.selectById(table), new Object[]{id}, rowMapper);
		return entityOrEmpty.isEmpty() ? null : entityOrEmpty.get(0);
	}

	@Override
	public T save(T entity) {
		if (entity.isNew()) {
			return create(entity);
		} else {
			return update(entity);
		}
	}

	protected T update(T entity) {
		final Map<String, Object> columns = preUpdate(entity, columns(entity));
		final Object idValue = columns.remove(table.getIdColumn());
		final String updateQuery = sqlGenerator.update(table, columns);
		columns.put(table.getIdColumn(), idValue);
		final Object[] queryParams = columns.values().toArray();
		jdbcOperations.update(updateQuery, queryParams);
		return postUpdate(entity);
	}

	protected Map<String,Object> preUpdate(T entity, Map<String, Object> columns) {
		return columns;
	}

	protected T create(T entity) {
		final Map<String, Object> columns = preCreate(columns(entity), entity);
		if (entity.getId() == null) {
			return createWithAutoGeneratedKey(entity, columns);
		} else {
			return createWithManuallyAssignedKey(entity, columns);
		}
	}

	private T createWithManuallyAssignedKey(T entity, Map<String, Object> columns) {
		final String createQuery = sqlGenerator.create(table, columns);
		final Object[] queryParams = columns.values().toArray();
		jdbcOperations.update(createQuery, queryParams);
		return postCreate(entity, null);
	}

	private T createWithAutoGeneratedKey(T entity, Map<String, Object> columns) {
		columns.remove(table.getIdColumn());
		final String createQuery = sqlGenerator.create(table, columns);
		final Object[] queryParams = columns.values().toArray();
		final GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbcOperations.update(new PreparedStatementCreator() {
			@Override
			public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
				final PreparedStatement ps = con.prepareStatement(createQuery, new String[]{table.getIdColumn()});
				for (int i = 0; i < queryParams.length; ++i) {
					ps.setObject(i + 1, queryParams[i]);
				}
				return ps;
			}
		}, key);
		return postCreate(entity, key.getKey());
	}

	protected Map<String, Object> preCreate(Map<String, Object> columns, T entity) {
		return columns;
	}

	private LinkedHashMap<String, Object> columns(T entity) {
		return new LinkedHashMap<String, Object>(rowUnmapper.mapColumns(entity));
	}

	protected T postUpdate(T entity) {
		return entity;
	}

	/**
	 * General purpose hook method that is called every time {@link #create} is called with a new entity.
	 * <p/>
	 * OVerride this method e.g. if you want to fetch auto-generated key from database
	 *
	 *
	 * @param entity Entity that was passed to {@link #create}
	 * @param generatedId ID generated during INSERT or NULL if not available/not generated.
	 * todo: Type should be ID, not Number
	 * @return Either the same object as an argument or completely different one
	 */
	protected T postCreate(T entity, Number generatedId) {
		return entity;
	}

	@Override
	public Iterable<T> save(Iterable<? extends T> entities) {
		List<T> ret = new ArrayList<T>();
		for (T t : entities) {
			ret.add(save(t));
		}
		return ret;
	}

	@Override
	public Iterable<T> findAll(Sort sort) {
		return jdbcOperations.query(sqlGenerator.selectAll(table, sort), rowMapper);
	}

	@Override
	public Page<T> findAll(Pageable page) {
		String query = sqlGenerator.selectAll(table, page);
		return new PageImpl<T>(jdbcOperations.query(query, rowMapper), page, count());
	}

}


