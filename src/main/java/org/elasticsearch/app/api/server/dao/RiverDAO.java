package org.elasticsearch.app.api.server.dao;

import org.elasticsearch.app.api.server.entities.River;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface RiverDAO extends JpaRepository<River, Long> {

    River findByRiverName(String name);

    River getByRiverName(String name);

    boolean existsByRiverName(String name);

}
