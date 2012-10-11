/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2010, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.dialect;

import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.MappingException;
import org.hibernate.dialect.function.NoArgSQLFunction;
import org.hibernate.dialect.function.VarArgsSQLFunction;
import org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtracter;
import org.hibernate.exception.spi.ViolatedConstraintNameExtracter;
import org.hibernate.internal.util.JdbcExceptionHelper;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.sql.ANSICaseFragment;
import org.hibernate.sql.ANSIJoinFragment;
import org.hibernate.sql.CaseFragment;
import org.hibernate.sql.JoinFragment;
import org.hibernate.type.DateType;
import org.hibernate.type.StandardBasicTypes;

/**
 * Informix dialect.<br>
 * <br>
 * Seems to work with Informix Dynamic Server Version 7.31.UD3,  Informix JDBC driver version 2.21JC3.
 *
 * @author Steve Molitor
 */
public class InformixDialect extends Dialect {

	/**
	 * Creates new <code>InformixDialect</code> instance. Sets up the JDBC /
	 * Informix type mappings.
	 */
	public InformixDialect() {
		super();
		
		registerCharacterTypeMappings();
		registerNumericTypeMappings();
		registerDateTimeTypeMappings();
		registerBinaryTypeMappings();
		
		registerFunctions();
	}
	
	protected void registerCharacterTypeMappings() {
		registerColumnType(Types.CHAR, "char($l)");
		registerColumnType(Types.VARCHAR, "varchar($l)");
		registerColumnType(Types.VARCHAR, 255, "varchar($l)");
		registerColumnType(Types.VARCHAR, 32739, "lvarchar($l)");
		// Prefer Smart-LOB types (CLOB and BLOB) over LOB types (TEXT and BYTE)
		registerColumnType(Types.LONGVARCHAR, "clob"); // or TEXT?
		registerColumnType(Types.CLOB, "clob");
	}

	protected void registerNumericTypeMappings() {
		registerColumnType(Types.BIT, "smallint"); // Informix doesn't have a bit type
		registerColumnType(Types.TINYINT, "smallint");
		registerColumnType(Types.SMALLINT, "smallint");
		registerColumnType(Types.INTEGER, "integer");
		// Prefer bigint over int8 (conserves space, more standard)
		registerColumnType(Types.BIGINT, "bigint"); // previously int8

		registerColumnType(Types.FLOAT, "smallfloat");
		registerColumnType(Types.REAL, "smallfloat");
		registerColumnType(Types.DOUBLE, "float");
		registerColumnType(Types.NUMERIC, "decimal"); // or MONEY
		registerColumnType(Types.DECIMAL, "decimal");
	}

	protected void registerDateTimeTypeMappings() {
		registerColumnType(Types.DATE, "date");
		registerColumnType(Types.TIME, "datetime hour to second");
		registerColumnType(Types.TIMESTAMP, "datetime year to fraction(5)");
	}
	
	protected void registerBinaryTypeMappings() {
		registerColumnType(Types.BOOLEAN, "boolean");
		registerColumnType(Types.BINARY, "byte");
		// Prefer Smart-LOB types (CLOB and BLOB) over LOB types (TEXT and BYTE)
		registerColumnType(Types.VARBINARY, "blob");
		registerColumnType(Types.LONGVARBINARY, "blob"); // or BYTE
		registerColumnType(Types.BLOB, "blob");
	}

	protected void registerFunctions() {
		registerFunction( "concat", new VarArgsSQLFunction( StandardBasicTypes.STRING, "(", "||", ")" ) );
		
		if (Boolean.getBoolean("org.hibernate.dialect.InformixDialect.enableCurrentDateFunction")) {
			if (Boolean.getBoolean("org.hibernate.dialect.InformixDialect.useSysdualForCurrentDateFunction")) {
				// Alternate version of current_date using sysmaster's sysdual table to avoid using "first 1" or "distinct"
				registerFunction("current_date", new NoArgSQLFunction("(select today from sysmaster:sysdual)", new DateType(), false));				
			} else {
				// Huge hack to combat the fact that Informix does not have a
				// current_date function.  This will probably fail in weird edge
				// cases.
				registerFunction("current_date", new NoArgSQLFunction("(select first 1 today from informix.systables)", new DateType(), false));			
			}
		}
	}
	
	// IDENTITY support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	/**
	 * Informix supports identity columns through the SERIAL and SERIAL8 types.
	 * Informix also supports sequences to generated identity values. Hibernate 
	 * iterates through strategies, picking the first that returns true. To ensure
	 * sequences are used, report false here.
	 */
	@Override
	public boolean supportsIdentityColumns() {
		return false;
	}

	@Override
	public boolean hasDataTypeInIdentityColumn() {
		return false;
	}

	@Override
	public String getIdentitySelectString(String table, String column, int type) throws MappingException {
		return type==Types.BIGINT ?
			"select dbinfo('bigserial') from systables where tabid=1" :
			"select dbinfo('sqlca.sqlerrd1') from systables where tabid=1";
	}
	
	@Override
	public String getIdentityColumnString(int type) throws MappingException {
		//return "generated by default as identity"; //not null ... (start with 1) is implied
		return type==Types.BIGINT ?
			"bigserial not null" :
			"serial not null";
	}
	
	@Override
	public String getIdentityInsertString() {
		return "0";
	}

	// SEQUENCE support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	
	@Override
	public boolean supportsSequences() {
		return true;
	}

	@Override
	public boolean supportsPooledSequences() {
		return true;
	}

	@Override
	public String getSequenceNextValString(String sequenceName) {
		return "select " + getSelectSequenceNextValString( sequenceName ) + " from systables where tabid=1";
	}

	@Override
	public String getSelectSequenceNextValString(String sequenceName) {
		return sequenceName + ".nextval";
	}
	
	@Override
	public String getCreateSequenceString(String sequenceName) {
		return "create sequence " + sequenceName;
	}
	
	@Override
	public String getDropSequenceString(String sequenceName) {
		//return "drop sequence " + sequenceName + " restrict";
		return "drop sequence " + sequenceName;
	}

	/**
	 * Informix treats sequences like a table from the standpoint of naming.
	 * Therefore, to retrieve the sequence name we must perform a join between
	 * systables and syssequences on the {@code}tabid column.
	 */
	@Override
	public String getQuerySequencesString() {
		return "select systables.tabname from systables,syssequences where systables.tabid = syssequences.tabid";
	}
	
	// GUID support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	/*
	 *  Informix does not have built-in support for this type of operation.
	 *  However, stored-procedures, ideally C-UDRs, can be used to make this happen.
	 *  Jacques Roy has authored a developerWorks article on this.
	 */

	
	// limit/offset support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	@Override
	public boolean supportsLimit() {
		return true;
	}

	@Override
	public boolean supportsLimitOffset() {
		return true;
	}

	@Override
	public boolean supportsVariableLimit() {
		return false;
	}
	
	/**
	 * Previously incorrectly claimed that Informix Dynamic Server (IDS) required using the maximim row number for the limit.
	 * Overriding explicitly so that user's of this dialect are aware (even though 'false' is the default inherited from Dialect).
	 */
	@Override
	public boolean useMaxForLimit() {
		return false;
	}
		
	@Override
	public String getLimitString(String query, int offset, int limit) {
		/* SQL Syntax:
		 * SELECT FIRST <limit> ...
		 * SELECT SKIP <offset> FIRST <limit> ...
		 */
		
		if (offset < 0 || limit < 0)
		{
			throw new IllegalArgumentException("Cannot perform limit query with negative limit and/or offset value(s)");
		}
		
		StringBuffer limitQuery = new StringBuffer(query.length() + 10);
		limitQuery.append(query);
		int indexOfEndOfSelect = query.toLowerCase().indexOf("select") + 6;

		if (offset == 0) {
			limitQuery.insert(indexOfEndOfSelect, " first " + limit);
		} else {
			limitQuery.insert(indexOfEndOfSelect, " skip " + offset + " first " + limit);
		}
		return limitQuery.toString();
	}

	// temporary table support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	/**
	 * Overrides {@link Dialect#supportsTemporaryTables()} to return
	 * {@code true} since Informix does, in fact, support temporary tables.
	 * 
	 * @return {@code true} when invoked
	 */
	@Override
	public boolean supportsTemporaryTables() {
		return true;
	}

	/**
	 * Overrides {@link Dialect#getCreateTemporaryTableString()} to return
	 * {@code create temp table}.
	 * 
	 * @return {@code create temp table} when invoked
	 */
	@Override
	public String getCreateTemporaryTableString() {
		return "create temp table";
	}
	
	// callable statement support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	// current timestamp support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	@Override
	public boolean supportsCurrentTimestampSelection() {
		return true;
	}

	@Override
	public boolean isCurrentTimestampSelectStringCallable() {
		return false;
	}

	@Override
	public String getCurrentTimestampSelectString() {
		return "select distinct current timestamp from informix.systables";
	}
	
	// SQLException support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	@Override
	public ViolatedConstraintNameExtracter getViolatedConstraintNameExtracter() {
        return EXTRACTER;
	}

	private static ViolatedConstraintNameExtracter EXTRACTER = new TemplatedViolatedConstraintNameExtracter() {

		/**
		 * Extract the name of the violated constraint from the given SQLException.
		 *
		 * @param sqle The exception that was the result of the constraint violation.
		 * @return The extracted constraint name.
		 */
		public String extractConstraintName(SQLException sqle) {
			String constraintName = null;
			
			int errorCode = JdbcExceptionHelper.extractErrorCode(sqle);
			if ( errorCode == -268 ) {
				constraintName = extractUsingTemplate( "Unique constraint (", ") violated.", sqle.getMessage() );
			}
			else if ( errorCode == -691 ) {
				constraintName = extractUsingTemplate( "Missing key in referenced table for referential constraint (", ").", sqle.getMessage() );
			}
			else if ( errorCode == -692 ) {
				constraintName = extractUsingTemplate( "Key value for constraint (", ") is still being referenced.", sqle.getMessage() );
			}
			
			if (constraintName != null) {
				// strip table-owner because Informix always returns constraint names as "<table-owner>.<constraint-name>"
				int i = constraintName.indexOf('.');
				if (i != -1) {
					constraintName = constraintName.substring(i + 1);
				}
			}

			return constraintName;
		}

	};
	
	// union subclass support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	
	/**
	 * Does this dialect support UNION ALL, which is generally a faster
	 * variant of UNION?
	 *
	 * @return True if UNION ALL is supported; false otherwise.
	 */
	@Override
	public boolean supportsUnionAll() {
		return true;
	}
	
	// miscellaneous support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	/**
	 * Create a {@link org.hibernate.sql.JoinFragment} strategy responsible
	 * for handling this dialect's variations in how joins are handled.
	 *
	 * @return This dialect's {@link org.hibernate.sql.JoinFragment} strategy.
	 */
	@Override
	public JoinFragment createOuterJoinFragment() {
		return new ANSIJoinFragment();
	}

	/**
	 * Create a {@link org.hibernate.sql.CaseFragment} strategy responsible
	 * for handling this dialect's variations in how CASE statements are
	 * handled.
	 *
	 * @return This dialect's {@link org.hibernate.sql.CaseFragment} strategy.
	 */
	@Override
	public CaseFragment createCaseFragment() {
		return new ANSICaseFragment();
	}
	
	/**
	 * The fragment used to insert a row without specifying any column values.
	 * Informix does not support this concept at present.
	 *
	 * @return The appropriate empty values clause.
	 */
	@Override
	public String getNoColumnsInsertString() {
		return "values (0)";
	}
	
	/**
	 * Overrides {@link InformixDialect2#toBooleanValueString(boolean)} to return
	 * {@code t} or {@code f}, not {@code 1} or {@code 0}.
	 * 
	 * @param value
	 *            the {@code boolean} value to translate
	 * 
	 * @return {@code t} or {@code f} if {@code value} is {@code true} or
	 *         {@code false} respectively
	 * 
	 * @see <a href="https://hibernate.onjira.com/browse/HHH-3551">HHH-3551</a>
	 */
	@Override
	public String toBooleanValueString(final boolean value) {
		return value ? "'t'" : "'f'";
	}
	
	// DDL support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	@Override
	public String getAddColumnString() {
		return "add";
	}

	/**
	 * The syntax used to add a primary key constraint to a table.
	 * Informix constraint name must be at the end.
	 * @return String
	 */
	@Override
	public String getAddPrimaryKeyConstraintString(String constraintName) {
		return " add constraint primary key constraint " + constraintName + " ";
	}

	/**
	 * The syntax used to add a foreign key constraint to a table.
	 * Informix constraint name must be at the end.
	 * @return String
	 */
	@Override
	public String getAddForeignKeyConstraintString(
			String constraintName,
			String[] foreignKey,
			String referencedTable,
			String[] primaryKey,
			boolean referencesPrimaryKey) {
		StringBuilder result = new StringBuilder( 30 )
				.append( " add constraint " )
				.append( " foreign key (" )
				.append( StringHelper.join( ", ", foreignKey ) )
				.append( ") references " )
				.append( referencedTable );

		if ( !referencesPrimaryKey ) {
			result.append( " (" )
					.append( StringHelper.join( ", ", primaryKey ) )
					.append( ')' );
		}

		result.append( " constraint " ).append( constraintName );

		return result.toString();
	}

	/**
	 * Overrides {@link Dialect#getCreateTemporaryTablePostfix()} to
	 * return "{@code with no log}" when invoked.
	 *
	 * @return "{@code with no log}" when invoked
	 */
	@Override
	public String getCreateTemporaryTablePostfix() {
		return "with no log";
	}

}
