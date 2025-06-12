/*
 * Copyright (c) 2018-2022 Cisco Systems, Inc. and others.  All rights reserved.
 */
package org.bgpdata.psqlquery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bgpdata.api.parsed.message.L3VpnPrefixPojo;

import static org.bgpdata.psqlquery.PsqlFunctions.create_psql_array;


public class L3VpnPrefixQuery extends Query {
    private final List<L3VpnPrefixPojo> records;

    public L3VpnPrefixQuery(List<L3VpnPrefixPojo> records){

        this.records = records;
    }


    public String[] genInsertStatement() {
        String [] stmt = { " INSERT INTO l3vpn_rib (hash_id,peer_hash_id,base_attr_hash_id,isIPv4," +
                "origin_as,prefix,prefix_len,timestamp," +
                "isWithdrawn,path_id,labels,isPrePolicy,isAdjRibIn,rd,ext_community_list) " +

                            " VALUES ",
//                "SELECT DISTINCT ON (hash_id) * FROM ( VALUES ",
//
//                ") t (hash_id,peer_hash_id,base_attr_hash_id,isIPv4," +
//                        "origin_as,prefix,prefix_len,timestamp," +
//                        "isWithdrawn,path_id,labels,isPrePolicy,isAdjRibIn,rd,ext_community_list) " +
//                        " ORDER BY hash_id,timestamp desc" +
                        " ON CONFLICT (peer_hash_id,hash_id) DO UPDATE SET timestamp=excluded.timestamp," +
                        "base_attr_hash_id=CASE excluded.isWithdrawn WHEN true THEN l3vpn_rib.base_attr_hash_id ELSE excluded.base_attr_hash_id END," +
                        "origin_as=CASE excluded.isWithdrawn WHEN true THEN l3vpn_rib.origin_as ELSE excluded.origin_as END," +
                        "isWithdrawn=excluded.isWithdrawn," +
                        "path_id=excluded.path_id, labels=excluded.labels," +
                        "isPrePolicy=excluded.isPrePolicy, isAdjRibIn=excluded.isAdjRibIn," +
                        "rd=excluded.rd,ext_community_list=excluded.ext_community_list "
        };
        return stmt;
    }

    public Map<String, String> genValuesStatement() {
        Map<String, String> values = new HashMap<>();

        for (L3VpnPrefixPojo pojo: records) {
            StringBuilder sb = new StringBuilder();

            sb.append("('");
            sb.append(pojo.getHash()); sb.append("'::uuid,");
            sb.append('\''); sb.append(pojo.getPeer_hash()); sb.append("'::uuid,");

            if (pojo.getBase_attr_hash().length() != 0) {
                sb.append('\'');
                sb.append(pojo.getBase_attr_hash());
                sb.append("'::uuid,");
            } else {
                sb.append("null::uuid,");
            }

            sb.append(pojo.getIPv4()); sb.append("::boolean,");

            sb.append(pojo.getOrigin_asn()); sb.append(',');

            sb.append('\''); sb.append(pojo.getPrefix()); sb.append('/');
            sb.append(pojo.getPrefix_len());
            sb.append("'::inet,");

            sb.append(pojo.getPrefix_len()); sb.append(',');

            sb.append('\''); sb.append(pojo.getTimestamp()); sb.append("'::timestamp,");
            sb.append(pojo.getWithdrawn()); sb.append(',');
            sb.append(pojo.getPath_id()); sb.append(',');
            sb.append('\''); sb.append(pojo.getLabels()); sb.append("',");
            sb.append(pojo.getPrePolicy()); sb.append("::boolean,");
            sb.append(pojo.getAdjRibIn()); sb.append("::boolean,");

            sb.append('\''); sb.append(pojo.getRd()); sb.append("',");
            sb.append(create_psql_array(pojo.getExt_community_list().split(" ")));

            sb.append(')');

            values.put(pojo.getHash(), sb.toString());
        }

        return values;
    }

}
