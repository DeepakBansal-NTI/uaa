/*
 * *****************************************************************************
 *      Cloud Foundry
 *      Copyright (c) [2009-2015] Pivotal Software, Inc. All Rights Reserved.
 *      This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *      You may not use this product except in compliance with the License.
 *
 *      This product includes a number of subcomponents with
 *      separate copyright notices and license terms. Your use of these
 *      subcomponents is subject to the terms and conditions of the
 *      subcomponent's license, as noted in the LICENSE file.
 * *****************************************************************************
 */

package org.cloudfoundry.identity.uaa.db;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.JdbcIdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.ZoneAlreadyExistsException;
import org.cloudfoundry.identity.uaa.zone.ZoneDoesNotExistsException;
import org.flywaydb.core.api.migration.spring.SpringJdbcMigration;
import org.flywaydb.core.internal.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StoreSubDomainAsLowerCase_V2_7_3 implements SpringJdbcMigration {

    static final String ID_ZONE_FIELDS = "id,version,created,lastmodified,name,subdomain,description";
    static final String IDENTITY_ZONES_QUERY = "select " + ID_ZONE_FIELDS + " from identity_zone ";

    Log logger = LogFactory.getLog(StoreSubDomainAsLowerCase_V2_7_3.class);

    @Override
    public synchronized void migrate(JdbcTemplate jdbcTemplate) throws Exception {
        RandomValueStringGenerator generator = new RandomValueStringGenerator(3);
        Map<String, List<IdentityZone>> zones = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        List<IdentityZone> identityZones = retrieveIdentityZones(jdbcTemplate);
        for (IdentityZone zone : identityZones) {
            addToMap(zone, zones, duplicates);
        }

        for (String s : duplicates) {
            logger.debug("Processing zone duplicates for subdomain:" + s);
            List<IdentityZone> dupZones = zones.get(s);
            for (int i=1; dupZones.size()>1 && i<dupZones.size(); i++) {
                IdentityZone dupZone = dupZones.get(i);
                String newsubdomain = null;
                while (newsubdomain==null) {
                    String potentialsubdomain = (dupZone.getSubdomain() +"-"+ generator.generate()).toLowerCase();
                    if (zones.get(potentialsubdomain)==null) {
                        newsubdomain = potentialsubdomain;
                    }
                }
                logger.debug(String.format("Updating zone id:%s; old subdomain: %s; new subdomain: %s;", dupZone.getId(), dupZone.getSubdomain(), newsubdomain));
                dupZone.setSubdomain(newsubdomain);
                dupZone = updateIdentityZone(dupZone, jdbcTemplate);
                zones.put(newsubdomain, Arrays.asList(dupZone));
            }
        }
        for (IdentityZone zone : identityZones) {
            String subdomain = zone.getSubdomain();
            if (StringUtils.hasText(subdomain) && !(subdomain.toLowerCase().equals(subdomain))) {
                logger.debug(String.format("Lowercasing zone subdomain for id:%s; old subdomain: %s; new subdomain: %s;", zone.getId(), zone.getSubdomain(), zone.getSubdomain().toLowerCase()));
                zone.setSubdomain(subdomain.toLowerCase());
                updateIdentityZone(zone, jdbcTemplate);
            }

        }
    }

    private IdentityZone updateIdentityZone(IdentityZone identityZone, JdbcTemplate jdbcTemplate) {
        String ID_ZONE_UPDATE_FIELDS = "version,lastmodified,name,subdomain,description".replace(",","=?,")+"=?";
        String UPDATE_IDENTITY_ZONE_SQL = "update identity_zone set " + ID_ZONE_UPDATE_FIELDS + " where id=?";

        try {
            jdbcTemplate.update(UPDATE_IDENTITY_ZONE_SQL, new PreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps) throws SQLException {
                    ps.setInt(1, identityZone.getVersion() + 1);
                    ps.setTimestamp(2, new Timestamp(new Date().getTime()));
                    ps.setString(3, identityZone.getName());
                    ps.setString(4, identityZone.getSubdomain().toLowerCase());
                    ps.setString(5, identityZone.getDescription());
                    ps.setString(6, identityZone.getId().trim());
                }
            });
        } catch (DuplicateKeyException e) {
            //duplicate subdomain
            throw new ZoneAlreadyExistsException(e.getMostSpecificCause().getMessage(), e);
        }
        return retrieveIdentityZone(identityZone.getId(), jdbcTemplate);
    }

    private IdentityZone retrieveIdentityZone(String id, JdbcTemplate jdbcTemplate) {
        String IDENTITY_ZONE_BY_ID_QUERY = IDENTITY_ZONES_QUERY + "where id=?";
        try {
            IdentityZone identityZone = jdbcTemplate.queryForObject(IDENTITY_ZONE_BY_ID_QUERY, mapper, id);
            return identityZone;
        } catch (EmptyResultDataAccessException x) {
            throw new ZoneDoesNotExistsException("Zone["+id+"] not found.", x);
        }
    }

    private List<IdentityZone> retrieveIdentityZones(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.query(IDENTITY_ZONES_QUERY, mapper);
    }

    private void addToMap(IdentityZone zone, Map<String, List<IdentityZone>> zones, Set<String> duplicates) {
        if (zone==null || zone.getSubdomain()==null) {
            return;
        }
        String subdomain = zone.getSubdomain().toLowerCase();
        if (zones.get(subdomain)==null) {
            List<IdentityZone> list = new LinkedList<>();
            list.add(zone);
            zones.put(subdomain, list);
        } else {
            logger.warn("Found duplicate zone for subdomain:"+subdomain);
            duplicates.add(subdomain);
            zones.get(subdomain).add(zone);
        }
    }

    private RowMapper<IdentityZone> mapper = (rs, rowNum) -> {
        IdentityZone identityZone = new IdentityZone();

        identityZone.setId(rs.getString(1).trim());
        identityZone.setVersion(rs.getInt(2));
        identityZone.setCreated(rs.getTimestamp(3));
        identityZone.setLastModified(rs.getTimestamp(4));
        identityZone.setName(rs.getString(5));
        identityZone.setSubdomain(rs.getString(6));
        identityZone.setDescription(rs.getString(7));

        return identityZone;
    };

}
