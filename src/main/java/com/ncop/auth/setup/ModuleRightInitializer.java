package com.ncop.auth.setup;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Standalone initializer for module rights. It seeds the module_rights collection in MongoDB
 * without running automatically with the main Spring Boot application.
 */
public class ModuleRightInitializer {

    public static void main(String[] args) {
        Properties props = new Properties();
        String[] filesToTry = {"application-dev.properties"};
        for (String file : filesToTry) {
            try (InputStream in = ModuleRightInitializer.class.getClassLoader().getResourceAsStream(file)) {
                if (in != null) {
                    props.load(in);
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

        List<ModuleSeed> seeds = new ArrayList<>();
        seeds.add(new ModuleSeed("DASHBOARD", "Dashboard", "Dashboard access"));
        seeds.add(new ModuleSeed("USER_MANAGEMENT", "User Management", "Manage users"));
        seeds.add(new ModuleSeed("ROLE_MANAGEMENT", "Role Management", "Manage roles"));
        seeds.add(new ModuleSeed("MODULE_RIGHT_MANAGEMENT", "Module Right Management", "Manage module rights"));
        seeds.add(new ModuleSeed("AUTHENTICATION", "Authentication", "Authentication and session operations"));
        seeds.add(new ModuleSeed("SALES", "Sales", "Sales module access"));
        seeds.add(new ModuleSeed("QA", "Quality Assurance", "QA module access"));
        seeds.add(new ModuleSeed("QC", "Quality Control", "QC module access"));
        seeds.add(new ModuleSeed("PRODUCT_MASTER", "Product Master", "Manage product master data"));
        seeds.add(new ModuleSeed("CLIENT_MASTER", "Client Master", "Manage client master data"));
        seeds.add(new ModuleSeed("PROFILE", "Profile", "View and manage own profile"));

        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(dbName);
            MongoCollection<Document> moduleRights = db.getCollection("module_rights");

            moduleRights.drop();

            for (ModuleSeed seed : seeds) {
                Document existing = moduleRights.find(Filters.eq("name", seed.name())).first();
                if (existing != null) {
                    ObjectId id = existing.getObjectId("_id");
                    moduleRights.updateOne(Filters.eq("_id", id), Updates.combine(
                            Updates.set("label", seed.label()),
                            Updates.set("description", seed.description())
                    ));
                    System.out.println("Updated module right '" + seed.name() + "' with id " + id.toHexString());
                    continue;
                }

                Document doc = new Document("name", seed.name())
                        .append("label", seed.label())
                        .append("description", seed.description());
                moduleRights.insertOne(doc);
                ObjectId insertedId = doc.getObjectId("_id");
                System.out.println("Inserted module right '" + seed.name() + "' with id " + insertedId.toHexString());
            }

            System.out.println("Module right seeding completed.");
        } catch (Exception e) {
            System.err.println("Module right seeding failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private record ModuleSeed(String name, String label, String description) {}

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
