package com.ncop.auth.setup;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Standalone user seeder. It reads the MongoDB URI and database from application properties
 * (application.properties, application-prod.properties, application-int.properties) or environment
 * variables MONGODB_URI / MONGODB_DATABASE. The MongoDB URI is required and the database name must
 * be provided either via properties, env, or included in the URI.
 *
 * It creates four users (admin, sales, qa, qc) with emails ending in @ncop.com and the same password
 * (Password@1). Role assignment is performed by looking up role documents in the roles collection
 * by name — roles must already exist in the roles collection.
 */
public class UserInitializer {

    private static final String PASSWORD = "Password@1";

    public static void main(String[] args) {
        // Load properties from classpath
        Properties props = new Properties();
        String[] filesToTry = {"application-dev.properties"};
        for (String f : filesToTry) {
            try (InputStream in = UserInitializer.class.getClassLoader().getResourceAsStream(f)) {
                if (in != null) props.load(in);
            } catch (IOException ignored) {
            }
        }

        // Resolve URI: check args -> resolve property placeholder -> env vars -> default
        String uri = null;
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            uri = args[0].trim();
        }
        if (uri == null || uri.isBlank()) {
            uri = resolveProperty(props.getProperty("spring.mongodb.uri"));
        }
        if (uri == null || uri.isBlank() || uri.startsWith("${")) {
            uri = System.getenv("MONGODB_URI_DEV");
        }
        if (uri == null || uri.isBlank()) {
            uri = System.getenv("MONGODB_URI");
        }
        if (uri == null || uri.isBlank()) {
            uri = System.getProperty("spring.mongodb.uri");
        }
        if (uri == null || uri.isBlank() || uri.startsWith("${")) {
            uri = "mongodb://localhost:27017";
        }

        String dbName = resolveProperty(props.getProperty("spring.mongodb.database"));
        if (dbName == null || dbName.isBlank() || dbName.startsWith("${")) {
            dbName = System.getenv("MONGODB_DATABASE_DEV");
        }
        if (dbName == null || dbName.isBlank()) {
            dbName = System.getenv("MONGODB_DATABASE");
        }
        if (dbName == null || dbName.isBlank()) {
            dbName = "dev";
        }

        try {
            com.mongodb.ConnectionString cs = new com.mongodb.ConnectionString(uri);
            if (cs.getDatabase() != null && !cs.getDatabase().isBlank()) {
                dbName = cs.getDatabase();
            }
        } catch (Exception e) {
            System.err.println("Note: Using database name '" + dbName + "' (ConnectionString parse: " + e.getMessage() + ")");
        }

        System.out.println("Using MongoDB URI: " + (uri.length() > 40 ? uri.substring(0, 40) + "..." : uri));
        System.out.println("Using MongoDB database: " + dbName);

        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(dbName);
            MongoCollection<Document> roles = db.getCollection("roles");
            MongoCollection<Document> users = db.getCollection("users");

            users.drop();

            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            String hashedPassword = encoder.encode(PASSWORD);

            // Users to create: local-part -> (roleName, userType)
            List<UserSpec> specs = Arrays.asList(
                    new UserSpec("admin", "ADMIN", "ADMIN"),
                    new UserSpec("sales", "SALES", "EMPLOYEE"),
                    new UserSpec("qa", "QA", "EMPLOYEE"),
                    new UserSpec("qc", "QC", "EMPLOYEE")
            );

            for (UserSpec spec : specs) {
                String email = spec.local + "@ncop.com";

                Document existing = users.find(Filters.eq("email", email)).first();
                if (existing != null) {
                    System.out.println("User with email '" + email + "' already exists (id=" + existing.getObjectId("_id").toHexString() + "), skipping.");
                    continue;
                }

                Document roleDoc = roles.find(Filters.eq("name", spec.roleName)).first();
                if (roleDoc == null) {
                    System.err.println("Role '" + spec.roleName + "' not found in roles collection — cannot create user '" + email + "'.");
                    continue;
                }

                ObjectId roleId = roleDoc.getObjectId("_id");
                List<String> roleIds = Arrays.asList(roleId.toHexString());
                List<String> moduleRights = getModuleRightsForRole(spec.roleName);

                Document userDoc = new Document("username", email)
                        .append("email", email)
                        .append("password", hashedPassword)
                        .append("firstName", capitalize(spec.local))
                        .append("lastName", "")
                        .append("roleIds", roleIds)
                        .append("moduleRights", moduleRights)
                        .append("userStatus", "ACTIVE")
                        .append("userType", spec.userType)
                        .append("createdOn", new Date())
                        .append("lastUpdatedOn", new Date());

                users.insertOne(userDoc);
                ObjectId uid = userDoc.getObjectId("_id");
                System.out.println("Inserted user '" + email + "' with id " + uid.toHexString() + " and roleId " + roleId.toHexString());
            }

            System.out.println("User seeding completed.");
        } catch (Exception e) {
            System.err.println("User seeding failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static List<String> getModuleRightsForRole(String roleName) {
        if ("ADMIN".equals(roleName)) {
            return Arrays.asList("DASHBOARD", "USER_MANAGEMENT", "ROLE_MANAGEMENT", "MODULE_RIGHT_MANAGEMENT", "AUTHENTICATION", "SALES", "QA", "QC");
        }
        if ("SALES".equals(roleName)) {
            return Arrays.asList("DASHBOARD", "SALES");
        }
        if ("QA".equals(roleName)) {
            return Arrays.asList("DASHBOARD", "QA");
        }
        if ("QC".equals(roleName)) {
            return Arrays.asList("DASHBOARD", "QC");
        }
        return List.of();
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private static String resolveProperty(String raw) {
        if (raw == null || raw.isBlank()) return null;
        raw = raw.trim();
        if (raw.startsWith("${") && raw.endsWith("}")) {
            String inner = raw.substring(2, raw.length() - 1);
            String varName = inner;
            String defaultValue = null;
            if (inner.contains(":")) {
                int idx = inner.indexOf(":");
                varName = inner.substring(0, idx);
                defaultValue = inner.substring(idx + 1);
            }
            String envVal = System.getenv(varName);
            if (envVal != null && !envVal.isBlank()) return envVal;
            return defaultValue;
        }
        return raw;
    }

    private record UserSpec(String local, String roleName, String userType) {}
}
