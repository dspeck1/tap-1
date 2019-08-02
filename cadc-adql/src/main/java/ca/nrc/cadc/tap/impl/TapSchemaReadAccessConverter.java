/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2019.                            (c) 2019.
*  Government of Canada                 Gouvernement du Canada
*  National Research Council            Conseil national de recherches
*  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
*  All rights reserved                  Tous droits réservés
*
*  NRC disclaims any warranties,        Le CNRC dénie toute garantie
*  expressed, implied, or               énoncée, implicite ou légale,
*  statutory, of any kind with          de quelque nature que ce
*  respect to the software,             soit, concernant le logiciel,
*  including without limitation         y compris sans restriction
*  any warranty of merchantability      toute garantie de valeur
*  or fitness for a particular          marchande ou de pertinence
*  purpose. NRC shall not be            pour un usage particulier.
*  liable in any event for any          Le CNRC ne pourra en aucun cas
*  damages, whether direct or           être tenu responsable de tout
*  indirect, special or general,        dommage, direct ou indirect,
*  consequential or incidental,         particulier ou général,
*  arising from the use of the          accessoire ou fortuit, résultant
*  software.  Neither the name          de l'utilisation du logiciel. Ni
*  of the National Research             le nom du Conseil National de
*  Council of Canada nor the            Recherches du Canada ni les noms
*  names of its contributors may        de ses  participants ne peuvent
*  be used to endorse or promote        être utilisés pour approuver ou
*  products derived from this           promouvoir les produits dérivés
*  software without specific prior      de ce logiciel sans autorisation
*  written permission.                  préalable et particulière
*                                       par écrit.
*
*  This file is part of the             Ce fichier fait partie du projet
*  OpenCADC project.                    OpenCADC.
*
*  OpenCADC is free software:           OpenCADC est un logiciel libre ;
*  you can redistribute it and/or       vous pouvez le redistribuer ou le
*  modify it under the terms of         modifier suivant les termes de
*  the GNU Affero General Public        la “GNU Affero General Public
*  License as published by the          License” telle que publiée
*  Free Software Foundation,            par la Free Software Foundation
*  either version 3 of the              : soit la version 3 de cette
*  License, or (at your option)         licence, soit (à votre gré)
*  any later version.                   toute version ultérieure.
*
*  OpenCADC is distributed in the       OpenCADC est distribué
*  hope that it will be useful,         dans l’espoir qu’il vous
*  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
*  without even the implied             GARANTIE : sans même la garantie
*  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
*  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
*  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
*  General Public License for           Générale Publique GNU Affero
*  more details.                        pour plus de détails.
*
*  You should have received             Vous devriez avoir reçu une
*  a copy of the GNU Affero             copie de la Licence Générale
*  General Public License along         Publique GNU Affero avec
*  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
*  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
*                                       <http://www.gnu.org/licenses/>.
*
*  $Revision: 5 $
*
************************************************************************
*/

package ca.nrc.cadc.tap.impl;

import java.net.URI;
import java.security.AccessControlException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;
import org.opencadc.gms.GroupClient;
import org.opencadc.gms.GroupURI;
import org.opencadc.gms.GroupUtil;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.IdentityManager;
import ca.nrc.cadc.cred.client.CredUtil;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.LocalAuthority;
import ca.nrc.cadc.tap.parser.ParserUtil;
import ca.nrc.cadc.tap.parser.navigator.ExpressionNavigator;
import ca.nrc.cadc.tap.parser.navigator.FromItemNavigator;
import ca.nrc.cadc.tap.parser.navigator.ReferenceNavigator;
import ca.nrc.cadc.tap.parser.navigator.SelectNavigator;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;

/**
 * Query converter that injects read-access constraints for tap_schema queries.
 *
 * @author pdowler, majorb
 */
public class TapSchemaReadAccessConverter extends SelectNavigator {
    
    private static Logger log = Logger.getLogger(TapSchemaReadAccessConverter.class);

    public static class AssetTable {
        
        public String keyColumn;
        public String ownerColumn;
        public String publicColumn;
        public String readGroupColumn;
        public String readWriteGroupColumn;

        AssetTable() {
        }

        AssetTable(String keyColumn, String ownerColumn, String publicColumn, String readGroupColumn, String readWriteGroupColumn) {
            this.keyColumn = keyColumn;
            this.ownerColumn = ownerColumn;
            this.publicColumn = publicColumn;
            this.readGroupColumn = readGroupColumn;
            this.readWriteGroupColumn = readWriteGroupColumn;
        }
    }

    public static final Map<String, AssetTable> ASSET_TABLES = new HashMap<String, AssetTable>();
    static {
        ASSET_TABLES.put("tap_schema.schemas", new AssetTable("schema_name", "owner_id", "read_anon", "read_only_group", "read_write_group"));
        ASSET_TABLES.put("tap_schema.tables", new AssetTable("table_name", "owner_id", "read_anon", "read_only_group", "read_write_group"));
        //ASSET_TABLES.put("tap_schema.columns", new AssetTable("column_name", null, null, null, null));
        ASSET_TABLES.put("tap_schema.columns", new AssetTable("column_name", "owner_id", "read_anon", "read_only_group", "read_write_group"));
    }

    private GroupClient gmsClient;
    private IdentityManager identityManager;

    public TapSchemaReadAccessConverter(IdentityManager identityManager) {
        super(new ExpressionNavigator(), new ReferenceNavigator(), new FromItemNavigator());
        this.identityManager = identityManager;
    }

    // testing support
    public void setGroupClient(GroupClient gmsClient) {
        this.gmsClient = gmsClient;
    }

    @Override
    public void visit(PlainSelect ps) {
        log.debug("start - visit(PlainSelect) " + ps);
        super.visit(ps);
        Expression exprAccessControl = accessControlExpression(ps);
        if (exprAccessControl == null) {
            return; 
        }

        Expression where = ps.getWhere();
        if (where == null)
            ps.setWhere(exprAccessControl);
        else {
            Parenthesis par = new Parenthesis(where);
            Expression and = new AndExpression(par, exprAccessControl);
            ps.setWhere(and);
        }
        log.debug("end - visit(PlainSelect) " + ps);
    }

    private Expression accessControlExpression(PlainSelect ps) {
        List<Expression> exprAcList = new ArrayList<Expression>();
        List<Table> fromTableList = ParserUtil.getFromTableList(ps);
        for (Table assetTable : fromTableList) {
            String fromTableWholeName = assetTable.getWholeTableName().toLowerCase();
            log.debug("check: " + fromTableWholeName);
            AssetTable at = ASSET_TABLES.get(fromTableWholeName);
            if (at != null) {
                Expression accessControlExpr = null;

                Expression publicByNullKey = publicByKeyColumn(assetTable, at.keyColumn);
                Expression publicByNullOwner = publicByNullOwner(assetTable, at.ownerColumn);
                Expression publicByPublicTrue = publicByPublicTrue(assetTable, at.ownerColumn, at.publicColumn);
                
                Expression pub = new Parenthesis(
                    new OrExpression(publicByNullKey,
                        new Parenthesis(
                            new OrExpression(publicByNullOwner, publicByPublicTrue))));
                
                if (isAuthenticated()) {
                
                    Expression authorizedByOwner = authorizedByOwner(assetTable, at.ownerColumn);
                    
                    List<String> gids = null;
                    log.debug("gmsClient: " + gmsClient);
                    if (gmsClient != null) {
                        gids = getGroupIDs(gmsClient);
                    }
                    
                    if (gids != null && gids.size() > 0) {
                        Expression authorizedByReadGroup = authorizedByReadGroup(assetTable, at.readGroupColumn, gids);
                        Expression authorizedByReadWriteGroup =  authorizedByReadWriteGroup(assetTable, at.readWriteGroupColumn, gids);
                        accessControlExpr = new Parenthesis(
                            new OrExpression(authorizedByOwner,
                                new OrExpression(authorizedByReadGroup, authorizedByReadWriteGroup)));
                    } else {
                        accessControlExpr = new Parenthesis(authorizedByOwner);
                    }
                    
                }

                if (accessControlExpr != null) {
                    accessControlExpr = new Parenthesis(new OrExpression(pub, accessControlExpr));
                } else {
                    accessControlExpr = pub;
                }

                exprAcList.add(accessControlExpr);
            } else {
                log.debug("not an asset table: " + fromTableWholeName);
            }
        }
        // AC list : one expression per asset table
        if (exprAcList.size() > 0) {
            return combineAndExpressions(exprAcList);
        }
        return null;
    }

    // if keyColumn is null, this is a join that didn't match any rows
    private Expression publicByKeyColumn(Table fromTable, String keyColumn) {
        Column columnMeta = useTableAliasIfExists(new Column(fromTable, keyColumn));
        IsNullExpression isNull = new IsNullExpression();
        isNull.setLeftExpression(columnMeta);
        return isNull;
    }
    
    private Expression publicByNullOwner(Table fromTable, String ownerColumn) {
        Column columnMeta = useTableAliasIfExists(new Column(fromTable, ownerColumn));
        IsNullExpression isNull = new IsNullExpression();
        isNull.setLeftExpression(columnMeta);
        return isNull;
    }
    
    private Expression publicByPublicTrue(Table fromTable, String ownerColumn, String publicColumn) {
        Column ownerColumnMeta = useTableAliasIfExists(new Column(fromTable, ownerColumn));
        IsNullExpression isNull = new IsNullExpression();
        isNull.setNot(true);
        isNull.setLeftExpression(ownerColumnMeta);
        
        Column publicColumnMeta = useTableAliasIfExists(new Column(fromTable, publicColumn));
        EqualsTo equals = new EqualsTo();
        equals.setLeftExpression(publicColumnMeta);
        equals.setRightExpression(new LongValue("1"));
        
        return new Parenthesis(new AndExpression(isNull, equals));
    }
    
    private Expression authorizedByOwner(Table fromTable, String ownerColumn) {
        Column columnMeta = useTableAliasIfExists(new Column(fromTable, ownerColumn));
        EqualsTo equals = new EqualsTo();
        equals.setLeftExpression(columnMeta);
        String owner = identityManager.toOwnerString(AuthenticationUtil.getCurrentSubject());
        equals.setRightExpression(new StringValue("'" + owner + "'"));
        return equals;
    }
    
    private Expression authorizedByReadGroup(Table fromTable, String readGroupColumn, List<String> groupIDs) {
        Column columnMeta = useTableAliasIfExists(new Column(fromTable, readGroupColumn));
        InExpression in = new InExpression();
        in.setLeftExpression(columnMeta);
        in.setItemsList(createStringExpressionList(groupIDs));
        return in;
    }
    
    private Expression authorizedByReadWriteGroup(Table fromTable, String readWriteGroupColumn, List<String> groupIDs) {
        Column columnMeta = useTableAliasIfExists(new Column(fromTable, readWriteGroupColumn));
        InExpression in = new InExpression();
        in.setLeftExpression(columnMeta);
        in.setItemsList(createStringExpressionList(groupIDs));
        return in;
    }
    
    private static ExpressionList createStringExpressionList(List<String> list) {
        List<Expression> expressions = new ArrayList<>(list.size());
        StringValue stringValue = null;
        for (String next : list) {
            stringValue = new StringValue("'" + next + "'");
            expressions.add(stringValue);
        }
        ExpressionList expressionList = new ExpressionList();
        expressionList.setExpressions(expressions);
        return expressionList;
    }

    private static Expression combineAndExpressions(List<Expression> exprList) {
        Expression rtn = null;
        for (Expression expr : exprList) {
            if (rtn == null)
                rtn = expr;
            else
                rtn = new AndExpression(rtn, expr);
        }
        return rtn;
    }

    private static Column useTableAliasIfExists(Column column) {
        Column rtn = null;
        Table newTable = null;

        Table table = column.getTable();
        if (table == null)
            rtn = column; // no treatment is made, return original column
        else {
            String alias = table.getAlias();
            if (alias == null || alias.equals(""))
                rtn = column; // no treatment is made, return original column
            else {
                newTable = new Table(null, alias);
                rtn = new Column(newTable, column.getColumnName());
            }
        }
        return rtn;
    }
    
    private boolean isAuthenticated() {
        Subject s = AuthenticationUtil.getCurrentSubject();
        return s != null && s.getPrincipals() != null && s.getPrincipals().size() > 0;
    }

    private List<String> getGroupIDs(GroupClient gmsClient) throws AccessControlException {
        try {
            List<String> groupIDs = new ArrayList<String>();

            try {
                if (ensureCredentials()) {
                    GroupClient gms = gmsClient;
                    if (gms == null) {
                        log.debug("Constructing new GMS Client");
                        LocalAuthority loc = new LocalAuthority();
                        URI gmsURI = loc.getServiceURI(Standards.GMS_GROUPS_01.toString());
                        gms = GroupUtil.getGroupClient(gmsURI);
                    }
                    List<GroupURI> groups = gms.getMemberships();
                    for (GroupURI group : groups) {
                        groupIDs.add(group.toString());
                    }
                }
            } catch (CertificateException ex) {
                throw new RuntimeException("failed to find group memberships (invalid proxy certficate)", ex);
            }
            return groupIDs;
        } finally {
        }
    }
    
    // testing support
    boolean ensureCredentials() throws AccessControlException,
            CertificateExpiredException, CertificateNotYetValidException {
        return CredUtil.checkCredentials();
    }
        
}