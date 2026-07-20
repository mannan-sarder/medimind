package com.mannan.medimind;

import java.io.Serializable;

/**
 * User model class representing the user profile.
 * Stores personal information like name, email, gender, age, etc.
 */
public class User implements Serializable {

    private String name;
    private String email;
    private String phone;
    private String gender;
    private int age;
    private String address;

    // Empty constructor (required for some libraries)
    public User() {
    }

    // Parameterized constructor with all fields
    public User(String name, String email, String phone, String gender, int age, String address) {
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.gender = gender;
        this.age = age;
        this.address = address;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }
}