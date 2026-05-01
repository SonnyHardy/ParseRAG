package com.sonny.parserag.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "usage_records")
@Data
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
