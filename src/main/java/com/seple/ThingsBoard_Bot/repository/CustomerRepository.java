package com.seple.ThingsBoard_Bot.repository;

import com.seple.ThingsBoard_Bot.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, String> {

    List<Customer> findAll();

    boolean existsByCustomerId(String customerId);
}
