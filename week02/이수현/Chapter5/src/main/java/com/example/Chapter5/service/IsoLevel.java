package com.example.Chapter5.service;

public enum IsoLevel {
  RC, RR;

  public static IsoLevel from(String s) {
    return IsoLevel.valueOf(s.toUpperCase());
  }
}