package com.example.Chapter5.service;

public enum ApplyMode {
  FOR_UPDATE,
  ATOMIC_UPDATE,
  COUNT_BASED;

  public static ApplyMode from(String s) {
    return ApplyMode.valueOf(s.toUpperCase());
  }
}
