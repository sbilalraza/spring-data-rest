/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.rest.webmvc.json;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.data.repository.support.Repositories;
import org.springframework.data.rest.webmvc.support.DomainClassResolver;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.NativeWebRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Translator for {@link Sort} arguments that is aware of Jackson-Mapping on domain classes. Jackson field names are
 * translated to {@link PersistentProperty} names. Domain class is looked up by resolving request URLs to mapped
 * repositories. {@link Sort} translation is skipped if a domain class cannot be resolved.
 *
 * @author Mark Paluch
 * @since 2.6
 */
@RequiredArgsConstructor
public class JacksonMappingAwareSortTranslator {

	private final @NonNull ObjectMapper objectMapper;
	private final @NonNull Repositories repositories;
	private final @NonNull DomainClassResolver domainClassResolver;
	private final @NonNull PersistentEntities persistentEntities;

	/**
	 * Translates Jackson field names within a {@link Sort} to {@link PersistentProperty} property names.
	 * 
	 * @param input must not be {@literal null}.
	 * @param parameter must not be {@literal null}.
	 * @param webRequest must not be {@literal null}.
	 * @return a {@link Sort} containing translated property names or {@literal null} the resulting {@link Sort} contains
	 *         no properties.
	 */
	protected Sort translateSort(Sort input, MethodParameter parameter, NativeWebRequest webRequest) {

		Assert.notNull(input, "Sort must not be null!");
		Assert.notNull(parameter, "MethodParameter must not be null!");
		Assert.notNull(webRequest, "NativeWebRequest must not be null!");

		Class<?> domainClass = domainClassResolver.resolve(parameter.getMethod(), webRequest);

		if (domainClass != null) {

			PersistentEntity<?, ?> persistentEntity = repositories.getPersistentEntity(domainClass);
			return new SortTranslator(persistentEntities, objectMapper).translateSort(input, persistentEntity);
		}

		return input;
	}

	/**
	 * Translates {@link Sort} orders from Jackson-mapped field names to {@link PersistentProperty} names.
	 *
	 * @author Mark Paluch
	 * @author Oliver Gierke
	 * @since 2.6
	 */
	@RequiredArgsConstructor
	public static class SortTranslator {

		private static final String DELIMITERS = "_\\.";
		private static final String ALL_UPPERCASE = "[A-Z0-9._$]+";
		private static final Pattern SPLITTER = Pattern.compile("(?:[%s]?([%s]*?[^%s]+))".replaceAll("%s", DELIMITERS));

		private final @NonNull PersistentEntities persistentEntities;
		private final @NonNull ObjectMapper objectMapper;

		/**
		 * Translates {@link Sort} orders from Jackson-mapped field names to {@link PersistentProperty} names. Properties
		 * that cannot be resolved are dropped.
		 *
		 * @param input must not be {@literal null}.
		 * @param rootEntity must not be {@literal null}.
		 * @return {@link Sort} with translated field names or {@literal null} if translation dropped all sort fields.
		 */
		public Sort translateSort(Sort input, PersistentEntity<?, ?> rootEntity) {

			Assert.notNull(input, "Sort must not be null!");
			Assert.notNull(rootEntity, "PersistentEntity must not be null!");

			List<Order> filteredOrders = new ArrayList<Order>();

			for (Order order : input) {

				List<String> iteratorSource = new ArrayList<String>();
				Matcher matcher = SPLITTER.matcher("_" + order.getProperty());

				while (matcher.find()) {
					iteratorSource.add(matcher.group(1));
				}

				WrappedProperties wrappedProperties = WrappedProperties.fromJacksonProperties(persistentEntities, rootEntity,
						objectMapper);
				MappedProperties mappedProperties = MappedProperties.fromJacksonProperties(rootEntity, objectMapper);

				String mappedPropertyPath = getMappedPropertyPath(wrappedProperties, mappedProperties, rootEntity,
						iteratorSource);

				if (mappedPropertyPath != null) {

					Order mappedOrder = new Order(order.getDirection(), mappedPropertyPath, order.getNullHandling());
					filteredOrders.add(order.isIgnoreCase() ? mappedOrder.ignoreCase() : mappedOrder);
				}
			}

			return filteredOrders.isEmpty() ? null : new Sort(filteredOrders);
		}

		private String getMappedPropertyPath(WrappedProperties wrappedProperties, MappedProperties mappedProperties,
				PersistentEntity<?, ?> rootEntity, List<String> iteratorSource) {

			List<String> persistentPropertyPath = mapPropertyPath(wrappedProperties, mappedProperties, rootEntity,
					iteratorSource);

			if (persistentPropertyPath.isEmpty()) {
				return null;
			}

			return StringUtils.collectionToDelimitedString(persistentPropertyPath, ".");
		}

		private List<String> mapPropertyPath(WrappedProperties wrappedProperties, MappedProperties mappedProperties,
				PersistentEntity<?, ?> rootEntity, List<String> iteratorSource) {

			List<String> persistentPropertyPath = new ArrayList<String>(iteratorSource.size());
			PersistentEntity<?, ?> currentType = rootEntity;
			MappedProperties currentProperties = mappedProperties;
			WrappedProperties currentWrappedProperties = wrappedProperties;

			for (String field : iteratorSource) {

				String fieldName = field.matches(ALL_UPPERCASE) ? field : StringUtils.uncapitalize(field);

				if (currentType == null) {
					return Collections.emptyList();
				}

				if (currentProperties == null) {
					currentProperties = MappedProperties.fromJacksonProperties(currentType, objectMapper);
				}

				if (currentWrappedProperties == null) {
					currentWrappedProperties = WrappedProperties.fromJacksonProperties(persistentEntities, currentType,
							objectMapper);
				}

				if (!currentProperties.hasPersistentPropertyForField(fieldName)
						&& !currentWrappedProperties.hasPersistentPropertiesForField(fieldName)) {
					return Collections.emptyList();
				}

				List<? extends PersistentProperty<?>> persistentProperties = getPersistentProperties(currentProperties,
						currentWrappedProperties, fieldName);

				for (PersistentProperty<?> persistentProperty : persistentProperties) {

					if (persistentProperty.isAssociation()) {
						return Collections.emptyList();
					}

					persistentPropertyPath.add(persistentProperty.getName());
				}

				currentType = persistentEntities
						.getPersistentEntity(persistentProperties.get(persistentProperties.size() - 1).getType());

				currentProperties = null;
				currentWrappedProperties = null;
			}

			return persistentPropertyPath;
		}
	}

	private static List<? extends PersistentProperty<?>> getPersistentProperties(MappedProperties currentProperties,
			WrappedProperties currentWrappedProperties, String fieldName) {

		if (currentWrappedProperties.hasPersistentPropertiesForField(fieldName)) {
			return currentWrappedProperties.getPersistentProperties(fieldName);
		}

		return Collections.singletonList(currentProperties.getPersistentProperty(fieldName));
	}
}
