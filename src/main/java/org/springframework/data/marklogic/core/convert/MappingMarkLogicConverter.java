package org.springframework.data.marklogic.core.convert;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.client.io.JacksonDatabindHandle;
import com.marklogic.client.query.StructuredQueryDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.marklogic.core.mapping.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

public class MappingMarkLogicConverter implements MarkLogicConverter, InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(MappingMarkLogicConverter.class);

    private static final String ISO_8601_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
    private static SimpleDateFormat simpleDateFormat8601 = new SimpleDateFormat(ISO_8601_FORMAT);
    static { simpleDateFormat8601.setTimeZone(TimeZone.getTimeZone("UTC")); }

    private MappingContext mappingContext;
    private ObjectMapper objectMapper;

    private XmlMapper xmlMapper = new XmlMapper();

    public MappingMarkLogicConverter(MappingContext<? extends MarkLogicPersistentEntity<?>, MarkLogicPersistentProperty> mappingContext) {
        this.mappingContext = mappingContext;
    }

    @Override
    public MappingContext<? extends MarkLogicPersistentEntity<?>, MarkLogicPersistentProperty> getMappingContext() {
        return mappingContext;
    }

    @Override
    public ConversionService getConversionService() {
        return null;
    }

    @Override
    public void write(Object source, DocumentDescriptor doc) {
        final MarkLogicPersistentEntity<?> entity = getMappingContext().getPersistentEntity(source.getClass());

        if (entity != null && entity.hasIdProperty()) {
            PersistentProperty idProperty = entity.getPersistentProperty(entity.getIdProperty().getName());

            if (Collection.class.isAssignableFrom(idProperty.getType()) || Map.class.isAssignableFrom(idProperty.getType()))
                throw new IllegalArgumentException("Collection types not supported as entity id");

            try {
                // TODO: Better way to do this?
                Object id = idProperty.getGetter().invoke(source);
                doc.setUri(getDocumentUris(asList(id), entity.getType()).get(0));
            } catch (Exception e) {
                // TODO: Support document URI templates
                throw new IllegalArgumentException("Unable to access value of @Id from " + idProperty.getName());
            }
        } else {
            throw new IllegalArgumentException("Your entity of type " + source.getClass().getName() + " does not have a method or field annotated with org.springframework.data.annotation.Id");
        }

        if (entity.getTypePersistenceStrategy() == TypePersistenceStrategy.COLLECTION) {
            if (doc.getMetadata() == null) doc.setMetadata(new DocumentMetadataHandle());
            doc.setMetadata(doc.getMetadata().withCollections(getTypeName(entity)));
        }

        JacksonDatabindHandle contentHandle = new JacksonDatabindHandle<>(source);
        if (entity.getDocumentFormat() == DocumentFormat.XML && xmlMapper != null) {
            contentHandle.setMapper(xmlMapper);
        } else {
            contentHandle.setMapper(objectMapper);
        }
        doc.setContent(contentHandle);
    }

    @Override
    public <R> R read(Class<R> clazz, DocumentDescriptor doc) {
        final MarkLogicPersistentEntity<?> entity = getMappingContext().getPersistentEntity(clazz);

        JacksonDatabindHandle<R> handle = new JacksonDatabindHandle<>(clazz);
        if (entity != null && entity.getDocumentFormat() == DocumentFormat.XML && xmlMapper != null) {
            handle.setMapper(xmlMapper);
        } else {
            handle.setMapper(objectMapper);
        }

        return doc.getRecord().getContent(handle).get();
    }

    @Override
    public <T> List<String> getDocumentUris(List<?> ids, Class<T> entityClass) {

        final List<String> uris = ids.stream()
                .flatMap(id -> {
                    if (entityClass != null) {
                        final MarkLogicPersistentEntity<?> entity = getMappingContext().getPersistentEntity(entityClass);
                        if (entity.getDocumentFormat() == DocumentFormat.XML && xmlMapper != null) {
                            return asList("/" + String.valueOf(id) + ".xml").stream();
                        } else {
                            return asList("/" + String.valueOf(id) + ".json").stream();
                        }
                    } else {
                        // Just from the ID we don't know the type, or can't infer it, so we need to "try" both.  The potential downside
                        // is if they have both JSON/XML for the same id - might get "odd" results?
                        return asList(
                            "/" + String.valueOf(id) + ".json",
                            "/" + String.valueOf(id) + ".xml"
                        ).stream();
                    }
                })
                .collect(Collectors.toList());

        return uris;
    }

    @Override
    public List<String> getDocumentUris(List<? extends Object> ids) {
        return getDocumentUris(ids, null);
    }

    @Override
    public <T> StructuredQueryDefinition wrapQuery(StructuredQueryDefinition query, Class<T> entityClass) {
        if (entityClass != null) {
            MarkLogicPersistentEntity entity = getMappingContext().getPersistentEntity(entityClass);
            if (entity != null && entity.getTypePersistenceStrategy() == TypePersistenceStrategy.COLLECTION) {
                List<String> collections = new ArrayList<>();
                Collections.addAll(collections, query.getCollections());
                collections.add(getTypeName(entity));
                query.setCollections(collections.toArray(new String[0]));
            }
        }
        return query;
    }

    @Override
    public void afterPropertiesSet() {
        objectMapper = new ObjectMapper()
                .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
                .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .setDateFormat(simpleDateFormat8601)
                .registerModule(new JavaTimeModule())
                // Since we don't configure to "wrap" in the class name we can't do "type scoped" path range indexes - could be a problem with larger data sets
                .disableDefaultTyping();

        try {
            Class.forName("com.fasterxml.jackson.dataformat.xml.XmlMapper", false, this.getClass().getClassLoader());
            xmlMapper = (XmlMapper) new XmlMapper()
                    .configure(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT, false)
                    .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
                    .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false)
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    .setDateFormat(simpleDateFormat8601)
                    .registerModule(new JavaTimeModule())
                    .disableDefaultTyping();
        } catch (ClassNotFoundException e) {
            LOG.warn("com.fasterxml.jackson.dataformat:jackson-dataformat-xml needs to be included in order to use Java->XML conversion");
        }

    }

    public String getTypeName(MarkLogicPersistentEntity entity) {
        // TODO: Add support for full class name for collection
        return entity.getType().getSimpleName();
    }
}
