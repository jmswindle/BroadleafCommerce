package org.broadleafcommerce.gwt.client.datasource.catalog;

import java.util.LinkedHashMap;

import org.broadleafcommerce.gwt.client.datasource.CeilingEntities;
import org.broadleafcommerce.gwt.client.datasource.EntityImplementations;
import org.broadleafcommerce.gwt.client.datasource.dynamic.ComplexValueMapStructureDataSource;
import org.broadleafcommerce.gwt.client.datasource.dynamic.module.ComplexValueMapStructureModule;
import org.broadleafcommerce.gwt.client.datasource.dynamic.module.DataSourceModule;
import org.broadleafcommerce.gwt.client.datasource.relations.ForeignKey;
import org.broadleafcommerce.gwt.client.datasource.relations.MapStructure;
import org.broadleafcommerce.gwt.client.datasource.relations.PersistencePerspective;
import org.broadleafcommerce.gwt.client.datasource.relations.PersistencePerspectiveItemType;
import org.broadleafcommerce.gwt.client.datasource.relations.operations.OperationType;
import org.broadleafcommerce.gwt.client.datasource.relations.operations.OperationTypes;
import org.broadleafcommerce.gwt.client.service.AppServices;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.smartgwt.client.data.DataSource;
import com.smartgwt.client.widgets.grid.ListGrid;

public class MediaMapDataSourceFactory {
	
	public static final MapStructure MAPSTRUCTURE = new MapStructure(String.class.getName(), "key", "Key", EntityImplementations.MEDIA, "categoryMedia", true);
	
	public static void createDataSource(String name, LinkedHashMap<String, String> keyMap, ListGrid associatedGrid, AsyncCallback<DataSource> cb) {
		OperationTypes operationTypes = new OperationTypes(OperationType.MAPSTRUCTURE, OperationType.MAPSTRUCTURE, OperationType.MAPSTRUCTURE, OperationType.MAPSTRUCTURE, OperationType.MAPSTRUCTURE);
		PersistencePerspective persistencePerspective = new PersistencePerspective(operationTypes, new String[]{}, new ForeignKey[]{});
		persistencePerspective.addPersistencePerspectiveItem(PersistencePerspectiveItemType.FOREIGNKEY, new ForeignKey("id", EntityImplementations.CATEGORY, null));
		persistencePerspective.addPersistencePerspectiveItem(PersistencePerspectiveItemType.MAPSTRUCTURE, MAPSTRUCTURE);
		DataSourceModule[] modules = new DataSourceModule[]{
			new ComplexValueMapStructureModule(CeilingEntities.CATEGORY, persistencePerspective, AppServices.DYNAMIC_ENTITY, associatedGrid)
		};
		ComplexValueMapStructureDataSource dataSource = new ComplexValueMapStructureDataSource(CeilingEntities.CATEGORY, name, persistencePerspective, AppServices.DYNAMIC_ENTITY, modules, keyMap);
		dataSource.buildFields(cb);
	}

}
