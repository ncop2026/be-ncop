package com.ncop.auth.setup;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.ncop.auth.model.Role;
import com.ncop.auth.repository.RoleRepository;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

/**
 * Standalone role initializer. It seeds the roles collection with the standard role names.
 * It does not auto-run with the main application.
 */
public class RoleInitializer {

    private static final List<String> REQUIRED_ROLES = List.of("ADMIN", "SALES", "QA", "QC");

    public static void seedRoles(RoleRepository roleRepository) {
        for (String roleName : REQUIRED_ROLES) {
            Role existing = roleRepository.findByName(roleName).orElse(null);
            if (existing == null) {
                roleRepository.save(new Role(roleName));
            }
        }
    }

    public static void main(String[] args) {
        Properties props = new Properties();
        String[] filesToTry = {"application-dev.properties"};
        for (String file : filesToTry) {
            try (InputStream in = RoleInitializer.class.getClassLoader().getResourceAsStream(file)) {
                if (in != null) {
                    props.load(in);
                    break;
                }
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

            roles.drop();

            for (String roleName : REQUIRED_ROLES) {
                Document found = roles.find(Filters.eq("name", roleName)).first();
                if (found == null) {
                    Document doc = new Document("name", roleName);
                    roles.insertOne(doc);
                    ObjectId id = doc.getObjectId("_id");
                    System.out.println("Inserted role '" + roleName + "' with id " + id.toHexString());
                }
            }

            System.out.println("Role seeding completed.");
        } catch (Exception e) {
            System.err.println("Role seeding failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
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
}
