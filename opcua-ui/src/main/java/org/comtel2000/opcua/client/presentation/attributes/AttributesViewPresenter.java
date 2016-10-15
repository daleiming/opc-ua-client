/*******************************************************************************
 * Copyright (c) 2016 comtel2000
 *
 * Licensed under the Apache License, version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *******************************************************************************/
package org.comtel2000.opcua.client.presentation.attributes;

import java.net.URL;
import java.util.EnumSet;
import java.util.ResourceBundle;

import javax.inject.Inject;

import org.comtel2000.opcua.client.presentation.binding.StatusBinding;
import org.comtel2000.opcua.client.service.OpcUaClientConnector;
import org.comtel2000.opcua.client.service.OpcUaConverter;
import org.comtel2000.opcua.client.service.OpcUaConverter.AccessLevel;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

public class AttributesViewPresenter implements Initializable {

  @Inject
  OpcUaClientConnector connection;

  @Inject
  StatusBinding state;

  @FXML
  private TableView<AttributeItem> table;

  @FXML
  private TableColumn<AttributeItem, String> attribute;

  @FXML
  private TableColumn<AttributeItem, String> value;

  @FXML
  private MenuItem copyItem;

  @FXML
  private MenuItem refreshItem;
  
  private final ObjectProperty<ReferenceDescription> selectedReference = new SimpleObjectProperty<>();
  private final ObjectProperty<DataValue> selectedDataValue = new SimpleObjectProperty<>();

  private final static Logger logger = LoggerFactory.getLogger(AttributesViewPresenter.class);

  @Override
  public void initialize(URL url, ResourceBundle rb) {

    attribute.setCellValueFactory(param -> param.getValue().attributeProperty());
    value.setCellValueFactory(param -> param.getValue().valueProperty());
    value.setCellFactory(new AttributeItemCellFactory());

    value.setOnEditCommit(event -> {
      try {
        event.consume();
        final ReferenceDescription rd = selectedReference.get();
        final DataValue dv = selectedDataValue.get();
        if (dv == null || rd == null) {
          logger.error("nothing selected");
          return;
        }
        Variant v = new Variant(OpcUaConverter.toWritableDataTypeObject(dv.getValue().getDataType().get(), event.getNewValue()));
        DataValue value = new DataValue(v, null, null);
        connection.writeValue(OpcUaConverter.toNodeId(rd.getNodeId()), value).whenCompleteAsync((s, t) -> {
          if (t != null) {
            logger.error(t.getMessage(), t);
          } else {
            logger.info("{} write '{}' -> '{}' [{}]", rd.getBrowseName(), event.getOldValue(), event.getNewValue(), s);
          }
          state.statusTextProperty().set(String.format("write to %s %s", rd.getBrowseName(), (s.isGood() ? "succeed" : "failed: " + s)));
          updateAttributes(rd);
        }, Platform::runLater);

      } catch (Exception e) {
        logger.error(e.getMessage(), e);
      }
    });

    state.showAttributeItemProperty().addListener((l, a, b) -> updateAttributes(b));

    bindContextMenu();
  }

  private void bindContextMenu() {
    copyItem.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
    refreshItem.disableProperty().bind(selectedReference.isNull());
  }

  @FXML
  void copyValue() {
    if (!table.isFocused()) {
      return;
    }
    AttributeItem item = table.getSelectionModel().getSelectedItem();
    if (item != null && item.valueProperty().get() != null) {
      Clipboard clipboard = Clipboard.getSystemClipboard();
      ClipboardContent content = new ClipboardContent();
      content.putString(item.valueProperty().get());
      clipboard.setContent(content);
    }
  }

  @FXML
  void refresh() {
    if (!table.isFocused()) {
      return;
    }
    updateAttributes(selectedReference.get());
  }
  
  private void updateAttributes(ReferenceDescription b) {
    table.getItems().clear();
    selectedReference.set(b);
    selectedDataValue.set(null);
    if (b == null) {
      return;
    }
    table.getItems().add(AttributeItem.get("DisplayName", b.getDisplayName().getText()));
    table.getItems().add(AttributeItem.get("BrowseName", b.getBrowseName().toParseableString()));
    table.getItems().add(AttributeItem.get("NodeId", b.getNodeId().toParseableString()));
    table.getItems().add(AttributeItem.get("NodeClass", String.valueOf(b.getNodeClass())));
    table.getItems().add(AttributeItem.get("ReferenceType", b.getReferenceTypeId().toParseableString()));
    table.getItems().add(AttributeItem.get("Forward", String.valueOf(b.getIsForward())));
    table.getItems().add(AttributeItem.get("TypeId", b.getTypeId().toParseableString()));
    table.getItems().add(AttributeItem.get("TypeDefinition", b.getTypeDefinition().toParseableString()));

    if (b.getNodeId().isLocal() && b.getNodeClass() == NodeClass.Variable) {
      connection
          .read(b.getNodeId().local().get(),
              Lists.newArrayList(AttributeId.Description.uid(), AttributeId.AccessLevel.uid(), AttributeId.UserAccessLevel.uid(), AttributeId.Value.uid()))
          .whenComplete((d, t) -> {
            if (t != null) {
              logger.error(t.getMessage(), t);
              return;
            }
            if (d.size() > 3) {
              DataValue descr = d.get(0);
              table.getItems().add(AttributeItem.get("Description", OpcUaConverter.toString(descr.getValue())));

              DataValue accessLevel = d.get(1);
              EnumSet<AccessLevel> level = OpcUaConverter.AccessLevel.fromMask((UByte) accessLevel.getValue().getValue());
              table.getItems().add(AttributeItem.get("AccessLevel", OpcUaConverter.toString(level)));
              DataValue userAccessLevel = d.get(2);
              EnumSet<AccessLevel> userLevel = OpcUaConverter.AccessLevel.fromMask((UByte) userAccessLevel.getValue().getValue());
              table.getItems().add(AttributeItem.get("UserAccessLevel", OpcUaConverter.toString(userLevel)));

              DataValue value = d.get(3);
              selectedDataValue.set(value);
              table.getItems()
                  .add(AttributeItem.get("Value", OpcUaConverter.toString(value.getValue()), level.contains(AccessLevel.CurrentWrite) && isSupported(value)));
              if (value.getValue().getDataType().isPresent()) {
                table.getItems().add(AttributeItem.get("Value (DataType)", OpcUaConverter.toDataTypeString(value.getValue().getDataType().get())));
              }
              if (value.getSourceTime() != null) {
                table.getItems().add(AttributeItem.get("Value (SourceTime)", OpcUaConverter.toString(value.getSourceTime())));
              }
              if (value.getSourcePicoseconds() != null) {
                table.getItems().add(AttributeItem.get("Value (SourcePicoseconds)", value.getSourcePicoseconds().toString()));
              }
              if (value.getServerTime() != null) {
                table.getItems().add(AttributeItem.get("Value (ServerTime)", OpcUaConverter.toString(value.getServerTime())));
              }
              if (value.getServerPicoseconds() != null) {
                table.getItems().add(AttributeItem.get("Value (ServerPicoseconds)", value.getServerPicoseconds().toString()));
              }
              table.getItems().add(AttributeItem.get("Value (StatusCode)", OpcUaConverter.toString(value.getStatusCode())));
            }
          });
    }
  }

  private boolean isSupported(DataValue value) {
    if (!value.getValue().getDataType().isPresent() || !value.getValue().getDataType().isPresent()) {
      return false;
    }
    if (!(value.getValue().getDataType().get().getIdentifier() instanceof UInteger)) {
      return false;
    }
    int type = ((UInteger) value.getValue().getDataType().get().getIdentifier()).intValue();
    return type > 0 && type < 17;
  }

}
