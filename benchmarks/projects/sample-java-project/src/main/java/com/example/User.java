package com.example;

/**
 * Sample domain class used by benchmark tasks.
 * - rename-001 renames field 'usrNm' to 'username'
 * - add-method-001 adds method 'getDisplayName()'
 */
public class User {

    /** Intentionally poor name — benchmark task renames this to 'username'. */
    private String usrNm;

    private String firstName;
    private String lastName;
    private int age;

    public User(String usrNm, String firstName, String lastName, int age) {
        this.usrNm = usrNm;
        this.firstName = firstName;
        this.lastName = lastName;
        this.age = age;
    }

    public String getUsrNm() { return usrNm; }
    public void setUsrNm(String usrNm) { this.usrNm = usrNm; }

    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public int getAge() { return age; }
}
